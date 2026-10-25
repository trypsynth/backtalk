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

package com.google.android.accessibility.talkback

import android.media.AudioManager
import android.os.Build
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Raises or lowers the accessibility volume by one step.
 *
 * `AudioManager.adjustStreamVolume` doesn't work for the accessibility stream on Bluetooth
 * headphones that control their own volume ("absolute volume"). Android passes the step to the
 * headphones without looking at the stream, so the accessibility volume stays where it is, and on
 * some phones, such as Samsung's, the media volume changes instead. Setting the volume, as the
 * system volume panel's slider does, changes the accessibility volume on every output.
 */
object AccessibilityVolumeStep {
  private const val TAG = "AccessibilityVolumeStep"

  /**
   * Moves the accessibility volume one step, staying above 0 so that speech can't go silent.
   *
   * @return true if the volume changed
   */
  @JvmStatic
  fun step(audioManager: AudioManager, decrease: Boolean, flags: Int): Boolean {
    val stream = AudioManager.STREAM_ACCESSIBILITY
    val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
    val min =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        audioManager.getStreamMinVolume(stream).coerceAtLeast(1)
      } else {
        1
      }
    val current = audioManager.getStreamVolume(stream)
    if (max <= min || (decrease && current <= min) || (!decrease && current >= max)) {
      return false
    }
    return try {
      audioManager.setStreamVolume(stream, if (decrease) current - 1 else current + 1, flags)
      true
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Couldn't set the accessibility volume: %s", e)
      false
    }
  }
}
