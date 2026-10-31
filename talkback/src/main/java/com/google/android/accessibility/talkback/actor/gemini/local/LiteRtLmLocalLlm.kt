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
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ModelInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs a Gemma 4 `.litertlm` file with LiteRT-LM. The engine takes seconds to load and holds about
 * a gigabyte or more of memory, so it loads on the first request and unloads after [idleUnloadMs]
 * without one.
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
    val backend = if (useGpu) Backend.GPU() else Backend.CPU()
    val config =
      EngineConfig(
        modelPath = modelFile.path,
        backend = backend,
        visionBackend = backend,
        maxNumTokens = maxTokens,
        maxNumImages = 1,
        cacheDir = cacheDir,
      )
    // Gemma 4 checkpoints carry a multi-token prediction head, which LiteRT-LM calls speculative
    // decoding: the model proposes several tokens per step and verifies them in one pass, which
    // measured about 30 percent faster on a Galaxy S25. The engine throws when the flag is set for
    // a model without that head, so ask the model file first and set it only when it says yes.
    val speculative = modelSupportsSpeculativeDecoding()
    val loaded = initializeEngine(config, enableSpeculativeDecoding = speculative)
    engine = loaded
    return loaded
  }

  /**
   * Whether the model file carries a multi-token prediction head. Null means its metadata could not
   * be read, in which case the engine keeps the model's own default rather than a guessed flag.
   */
  private fun modelSupportsSpeculativeDecoding(): Boolean? =
    try {
      ModelInfo.from(modelFile.path).use { modelInfo ->
        (modelInfo as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      Log.i(TAG, "Could not read the model's capabilities", e)
      null
    }

  /** Sets LiteRT-LM's experimental flag, then builds and initializes the engine under it. */
  @OptIn(ExperimentalApi::class)
  private fun initializeEngine(config: EngineConfig, enableSpeculativeDecoding: Boolean?): Engine {
    ExperimentalFlags.enableSpeculativeDecoding = enableSpeculativeDecoding
    return Engine(config).also { it.initialize() }
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
