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

import android.content.SharedPreferences

/** The settings for running Describe image and Describe screen on the phone instead of the cloud. */
object OnDeviceAiSettings {
  const val PREF_PROVIDER = "pref_on_device_ai_provider"
  const val PREF_MODEL = "pref_on_device_ai_model"
  const val PREF_GPU = "pref_on_device_ai_gpu"
  const val PREF_SHORT_PROMPTS = "pref_on_device_ai_short_prompts"
  const val PREF_TIMEOUT = "pref_on_device_ai_timeout_seconds"
  const val PREF_IGNORE_MEMORY_LIMITS = "pref_on_device_ai_ignore_memory_limits"

  const val PROVIDER_CLOUD = "cloud"
  const val PROVIDER_DEVICE = "device"

  /** Android reports a little less RAM than the phone's label says, so allow for that. */
  private const val RAM_SLACK = 0.85
  private const val GIB = 1024L * 1024L * 1024L
  // A 12 GB phone reports about 10.9 GB of RAM, and an 8 GB one about 7.4 GB.
  private const val DEFAULT_E2B_MIN_RAM_GIB = 10L
  // The default model's file is at most this fraction of the RAM, a fifth.
  private const val DEFAULT_MAX_RAM_SHARE = 5

  fun isOnDevice(prefs: SharedPreferences): Boolean =
    prefs.getString(PREF_PROVIDER, PROVIDER_CLOUD) == PROVIDER_DEVICE

  fun setOnDevice(prefs: SharedPreferences, onDevice: Boolean) {
    prefs.edit().putString(PREF_PROVIDER, if (onDevice) PROVIDER_DEVICE else PROVIDER_CLOUD).apply()
  }

  /**
   * The model the user chose. When they have not chosen one, this is [defaultModel] for a phone
   * with [totalRamBytes] of RAM, or Gemma 4 E2B when the RAM is not known.
   */
  fun preferredModel(prefs: SharedPreferences, totalRamBytes: Long? = null): LocalModel =
    LocalModel.fromId(prefs.getString(PREF_MODEL, null))
      ?: totalRamBytes?.let { defaultModel(it) }
      ?: LocalModel.E2B

  /** Whether a phone with [totalRamBytes] of RAM has enough memory for [model]. */
  fun fits(model: LocalModel, totalRamBytes: Long): Boolean =
    totalRamBytes >= model.minTotalRamBytes * RAM_SLACK

  /**
   * Whether a model file of [modelBytes] can load now, with [availableBytes] of memory free, as
   * Android counts it. A model that loads into less memory than its own size pushes the phone so low
   * that Android stops apps, and in the end the model itself.
   */
  fun hasFreeMemoryFor(modelBytes: Long, availableBytes: Long, lowMemory: Boolean): Boolean =
    !lowMemory && availableBytes >= modelBytes

  /**
   * The models to offer on a phone with [totalRamBytes] of RAM: the ones that fit, and any that
   * are already on the phone, so that they can still be chosen or deleted.
   */
  fun modelsFor(totalRamBytes: Long, isInstalled: (LocalModel) -> Boolean): List<LocalModel> =
    LocalModel.entries.filter { fits(it, totalRamBytes) || isInstalled(it) }

  /**
   * The model to use when the user has not chosen one. It should load with the memory a phone
   * usually has free, not just fit in its RAM: on a Pixel 9a, with 8 GB, Gemma 4 E2B took about 3
   * GB while it answered when only about 1.6 GB was free, so Android swapped, and it took over a
   * minute. So Gemma 4 E2B is the default from 12 GB of RAM. Below that, the default is the largest
   * model no bigger than a fifth of the RAM, preferring ones that are not experimental, or else the
   * smallest model that fits.
   */
  fun defaultModel(totalRamBytes: Long): LocalModel {
    if (totalRamBytes >= DEFAULT_E2B_MIN_RAM_GIB * GIB) {
      return LocalModel.E2B
    }
    val fitting = LocalModel.entries.filter { fits(it, totalRamBytes) }
    return fitting
      .filter { it.sizeBytes <= totalRamBytes / DEFAULT_MAX_RAM_SHARE }
      .sortedWith(compareBy<LocalModel> { it.experimental }.thenByDescending { it.sizeBytes })
      .firstOrNull() ?: fitting.minByOrNull { it.sizeBytes } ?: LocalModel.E2B
  }

  fun setPreferredModel(prefs: SharedPreferences, model: LocalModel) {
    prefs.edit().putString(PREF_MODEL, model.id).apply()
  }

  fun useGpu(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_GPU, false)

  fun setUseGpu(prefs: SharedPreferences, useGpu: Boolean) {
    prefs.edit().putBoolean(PREF_GPU, useGpu).apply()
  }

  /** Whether Describe screen asks for less detail, to answer sooner. Off keeps the full prompt. */
  fun shortPrompts(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHORT_PROMPTS, false)

  fun setShortPrompts(prefs: SharedPreferences, short: Boolean) {
    prefs.edit().putBoolean(PREF_SHORT_PROMPTS, short).apply()
  }

  /**
   * Whether to let the user choose a model that needs more memory than the phone has, and load it
   * without checking for free memory. A developer setting, to test what happens when Android stops
   * the model.
   */
  fun ignoreMemoryLimits(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(PREF_IGNORE_MEMORY_LIMITS, false)

  /** How long to wait for an answer, in seconds, that the user can pick from. */
  val TIMEOUT_CHOICES_SECONDS = listOf(60, 120, 180, 300, 600)

  /** The cloud gives up after 35 seconds, which a model on the phone often cannot meet. */
  const val DEFAULT_TIMEOUT_SECONDS = 180

  fun timeoutSeconds(prefs: SharedPreferences): Int =
    prefs.getInt(PREF_TIMEOUT, DEFAULT_TIMEOUT_SECONDS).takeIf { it in TIMEOUT_CHOICES_SECONDS }
      ?: DEFAULT_TIMEOUT_SECONDS

  fun setTimeoutSeconds(prefs: SharedPreferences, seconds: Int) {
    prefs.edit().putInt(PREF_TIMEOUT, seconds).apply()
  }

  /** Why a phone can or cannot run on-device AI. */
  enum class Support {
    OK,
    /** The model library only ships for 64-bit ARM. */
    UNSUPPORTED_CPU,
    NOT_ENOUGH_RAM,
  }

  fun support(model: LocalModel, totalRamBytes: Long, supportedAbis: List<String>): Support =
    when {
      "arm64-v8a" !in supportedAbis -> Support.UNSUPPORTED_CPU
      !fits(model, totalRamBytes) -> Support.NOT_ENOUGH_RAM
      else -> Support.OK
    }
}
