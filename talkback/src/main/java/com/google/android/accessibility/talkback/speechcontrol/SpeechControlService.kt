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

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.google.android.libraries.accessibility.utils.log.LogUtils
import fyi.quin.backtalk.speech.ISpeakingCallback
import fyi.quin.backtalk.speech.ISpeechControl

class SpeechControlService : Service() {
  private val mainHandler = Handler(Looper.getMainLooper())

  private val binder =
    object : ISpeechControl.Stub() {
      override fun speak(
        text: String?,
        interrupt: Boolean,
        rate: Float,
        pitch: Float,
        volume: Float,
      ) {
        if (text == null) return
        onMainThread { SpeechControl.speak(text, interrupt, rate, pitch, volume) }
      }

      override fun stop() {
        onMainThread { SpeechControl.stop() }
      }

      override fun pause(pause: Boolean) {
        onMainThread { SpeechControl.pause(pause) }
      }

      override fun isSpeaking(callback: ISpeakingCallback?) {
        if (callback == null) return
        if (!onMainThread { answer(callback, SpeechControl.isSpeaking()) }) {
          answer(callback, false)
        }
      }
    }

  override fun onBind(intent: Intent): IBinder = binder

  private fun onMainThread(block: () -> Unit): Boolean {
    if (!SpeechControl.isAllowed(this)) {
      LogUtils.i(TAG, "Ignored a call, since other apps may not speak")
      return false
    }
    mainHandler.post(block)
    return true
  }

  private fun answer(callback: ISpeakingCallback, speaking: Boolean) {
    try {
      callback.onResult(speaking)
    } catch (e: RemoteException) {
      LogUtils.w(TAG, "The app that asked is gone: %s", e)
    }
  }

  private companion object {
    const val TAG = "SpeechControlService"
  }
}
