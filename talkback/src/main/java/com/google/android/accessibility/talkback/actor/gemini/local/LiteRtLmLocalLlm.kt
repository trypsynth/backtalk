/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor.gemini.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs a Gemma 4 `.litertlm` file with LiteRT-LM. The engine takes seconds to load and holds about
 * a gigabyte or more of memory, so it loads on the first request and unloads after [idleUnloadMs]
 * without one.
 *
 * NPU-optimized files (e.g. `gemma-4-E2B-it_qualcomm_sm8750.litertlm` for Snapdragon 8 Elite) run
 * with [Backend.NPU] for lower latency and battery use, which matters for a screen reader that
 * describes every image and screen. Like the Google AI Edge Gallery, the NPU libraries come from
 * `context.applicationInfo.nativeLibraryDir` (the `litert-npu-runtime-qualcomm` AAR). When NPU
 * loading fails, the caller should retry with CPU/GPU.
 */
class LiteRtLmLocalLlm(
  context: Context,
  private val modelFile: File,
  private val useGpu: Boolean,
  /** The most tokens for a prompt plus its answer, or null to use what the model file says. */
  private val maxTokens: Int? = null,
  private val idleUnloadMs: Long = DEFAULT_IDLE_UNLOAD_MS,
  /** Runs before each load, and throws [LocalLlmMemoryException] to stop it. */
  private val beforeLoad: () -> Unit = {},
  private val useNpu: Boolean = false,
  private val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir,
) : LocalLlm {
  private val cacheDir = context.cacheDir.path
  private val generateLock = Any()
  private val unloader =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "on-device-ai-unload").apply { isDaemon = true }
    }
  private var engine: Engine? = null
  private var unloadTask: ScheduledFuture<*>? = null
  @Volatile private var conversation: Conversation? = null
  @Volatile private var cancelRequested = false

  override fun generate(prompt: String, jpeg: ByteArray?): String =
    synchronized(generateLock) {
      cancelRequested = false
      generate(prompt, jpeg) { cancelRequested }
    }

  /**
   * Like [generate], but stops when [isCancelled] returns true, instead of using the flag that
   * [cancel] sets and [generate] clears. A cancel that comes before this starts, while the engine
   * waits for an earlier answer or loads, is then not lost. [cancel] still stops an answer that has
   * started.
   */
  fun generate(prompt: String, jpeg: ByteArray?, isCancelled: () -> Boolean): String =
    synchronized(generateLock) {
      unloadTask?.cancel(false)
      try {
        if (isCancelled()) throw LocalLlmCancelledException()
        val started = SystemClock.elapsedRealtime()
        val loaded = loadEngine()
        val loadMs = SystemClock.elapsedRealtime() - started
        val contents =
          if (jpeg != null) Contents.of(Content.ImageBytes(jpeg), Content.Text(prompt))
          else Contents.of(prompt)
        val answer =
          loaded.createConversation().use { current ->
            conversation = current
            if (isCancelled()) throw LocalLlmCancelledException()
            current.sendMessage(contents).toString()
          }
        Log.i(TAG, "Answered in ${SystemClock.elapsedRealtime() - started} ms (load $loadMs ms)")
        answer
      } catch (e: LocalLlmCancelledException) {
        throw e
      } catch (e: LocalLlmMemoryException) {
        throw e
      } catch (e: Throwable) {
        if (isCancelled()) throw LocalLlmCancelledException()
        // A failed engine may be half loaded, so drop it and load afresh next time.
        unload()
        throw LocalLlmException(e.message ?: e.javaClass.simpleName, e)
      } finally {
        conversation = null
        scheduleUnload()
      }
    }

  override fun cancel() {
    cancelRequested = true
    try {
      conversation?.cancelProcess()
    } catch (e: Exception) {
      Log.w(TAG, "Could not cancel", e)
    }
  }

  /** Frees the model's memory now. */
  fun close() {
    synchronized(generateLock) {
      unloadTask?.cancel(false)
      unload()
    }
    unloader.shutdown()
  }

  private fun loadEngine(): Engine {
    engine?.let {
      return it
    }
    beforeLoad()
    // NPU files are SoC-specific and only run on Backend.NPU. Portable files use the GPU switch.
    // Vision uses the same backend as text so the whole request stays on one accelerator.
    // CPU thread count is capped at 4: XNNPack scales well to 4 threads on phones, and more
    // threads mostly add contention with TalkBack's own event handling on the same cores.
    val cpuThreads = minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    val backend =
      when {
        useNpu -> Backend.NPU(nativeLibraryDir)
        useGpu -> Backend.GPU()
        else -> Backend.CPU(threadCount = cpuThreads)
      }
    val config =
      EngineConfig(
        modelPath = modelFile.path,
        backend = backend,
        visionBackend = backend,
        maxNumTokens = maxTokens,
        maxNumImages = 1,
        cacheDir = cacheDir,
      )
    // If the NPU libraries are missing or the SoC does not match, surface it as a normal
    // load failure so the caller can fall back to GPU/CPU instead of crashing.
    try {
      return Engine(config).also {
        it.initialize()
        engine = it
      }
    } catch (e: LocalLlmMemoryException) {
      throw e
    } catch (e: Throwable) {
      if (useNpu) {
        Log.w(TAG, "NPU load failed, caller should retry with GPU/CPU", e)
      }
      throw e
    }
  }

  private fun unload() {
    try {
      engine?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Could not close the engine", e)
    }
    engine = null
  }

  private fun scheduleUnload() {
    if (engine == null || unloader.isShutdown) return
    unloadTask =
      unloader.schedule(
        {
          synchronized(generateLock) {
            Log.i(TAG, "Unloading the idle model")
            unload()
          }
        },
        idleUnloadMs,
        TimeUnit.MILLISECONDS,
      )
  }

  companion object {
    private const val TAG = "LiteRtLmLocalLlm"
    const val DEFAULT_IDLE_UNLOAD_MS = 2 * 60 * 1000L
  }
}
