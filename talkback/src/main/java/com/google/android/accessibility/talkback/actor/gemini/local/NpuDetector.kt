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

import android.os.Build
import android.util.Log

/**
 * Detects Qualcomm Snapdragon SoCs that have an NPU-optimized Gemma 4 E2B build in
 * `litert-community/gemma-4-E2B-it-litert-lm`.
 *
 * NPU models are compiled per-SoC (e.g. `gemma-4-E2B-it_qualcomm_sm8750.litertlm`), so the phone
 * must run the file that matches its own chip. The SoC model comes from [Build.SOC_MODEL] on API
 * 31+ with a fallback to the `ro.soc.model`, `ro.board.platform` and `ro.hardware` system
 * properties, all normalized to lower case without separators (e.g. "sm8750", "qcs8275").
 *
 * See https://developers.google.com/edge/litert/next/litert_lm_npu (Qualcomm AI Engine Direct).
 */
object NpuDetector {
  private const val TAG = "NpuDetector"

  /** SoC models with a published NPU build of Gemma 4 E2B, mapped to the [LocalModel] id. */
  private val SOC_TO_MODEL_ID =
    mapOf(
      // Snapdragon 8 Elite (SM8750).
      "sm8750" to "e2b-npu-sm8750",
      // Qualcomm QCS8275 (IoT).
      "qcs8275" to "e2b-npu-qcs8275",
    )

  /**
   * The SoC model of this phone in normalized form, or null when it cannot be determined.
   * For example "sm8750" for Snapdragon 8 Elite, "sm8650" for 8 Gen 3.
   */
  fun socModel(): String? {
    // API 31+: Build.SOC_MODEL, e.g. "SM8750".
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        Build.SOC_MODEL?.let { normalize(it)?.let { normalized -> return normalized } }
      } catch (e: Exception) {
        Log.w(TAG, "Could not read Build.SOC_MODEL", e)
      }
    }
    // Fallbacks via system properties, e.g. ro.soc.model=SM8750.
    for (prop in listOf("ro.soc.model", "ro.board.platform", "ro.hardware")) {
      readSystemProperty(prop)?.let { normalize(it)?.let { normalized -> return normalized } }
    }
    return null
  }

  /** The NPU-optimized [LocalModel] for this phone, or null when its SoC has no NPU build. */
  fun npuModelForThisPhone(): LocalModel? {
    val soc = socModel() ?: return null
    val id = SOC_TO_MODEL_ID[soc] ?: return null
    return LocalModel.fromId(id)
  }

  /** Whether this phone has an NPU-optimized Gemma build available. */
  fun hasNpuModel(): Boolean = npuModelForThisPhone() != null

  /** Normalizes a raw SoC string to lower case alphanumerics, e.g. "SM8750" to "sm8750". */
  internal fun normalize(raw: String): String? {
    val cleaned = raw.lowercase().filter { it.isLetterOrDigit() }
    return cleaned.ifEmpty { null }
  }

  private fun readSystemProperty(name: String): String? {
    return try {
      val systemProperties = Class.forName("android.os.SystemProperties")
      val get = systemProperties.getMethod("get", String::class.java, String::class.java)
      (get.invoke(null, name, "") as? String)?.ifEmpty { null }
    } catch (e: Exception) {
      Log.w(TAG, "Could not read $name", e)
      null
    }
  }
}
