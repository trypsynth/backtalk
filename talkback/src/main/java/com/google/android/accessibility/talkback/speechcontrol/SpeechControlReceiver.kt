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

package com.google.android.accessibility.talkback.speechcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.libraries.accessibility.utils.log.LogUtils

class SpeechControlReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!SpeechControl.isAllowed(context)) {
      LogUtils.i(TAG, "Ignored %s, since other apps may not speak", intent.action)
      return
    }
    when (intent.action) {
      SpeechControl.ACTION_SPEAK -> {
        val text = intent.getCharSequenceExtra(SpeechControl.EXTRA_TEXT) ?: return
        SpeechControl.speak(
          text,
          interrupt = intent.getBooleanExtra(SpeechControl.EXTRA_INTERRUPT, false),
          rate = floatExtra(intent, SpeechControl.EXTRA_RATE),
          pitch = floatExtra(intent, SpeechControl.EXTRA_PITCH),
          volume = floatExtra(intent, SpeechControl.EXTRA_VOLUME),
        )
      }
      SpeechControl.ACTION_STOP -> SpeechControl.stop()
      SpeechControl.ACTION_PAUSE ->
        SpeechControl.pause(intent.getBooleanExtra(SpeechControl.EXTRA_PAUSE, true))
    }
  }

  private companion object {
    const val TAG = "SpeechControlReceiver"

    fun floatExtra(intent: Intent, name: String): Float =
      when (@Suppress("DEPRECATION") val value = intent.extras?.get(name)) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull() ?: 1f
        else -> 1f
      }
  }
}
