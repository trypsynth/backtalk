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

import android.content.Context
import android.os.Bundle
import androidx.annotation.MainThread
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.SpeechParam
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_PROSODY_FROM_CALLER
import com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_INTERRUPT
import com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_QUEUE
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

object SpeechControl {
  const val PERMISSION = "fyi.quin.backtalk.permission.SPEAK"

  const val ACTION_SPEAK = "fyi.quin.backtalk.action.SPEAK"
  const val ACTION_STOP = "fyi.quin.backtalk.action.STOP_SPEECH"
  const val ACTION_PAUSE = "fyi.quin.backtalk.action.PAUSE_SPEECH"

  const val EXTRA_TEXT = "text"
  const val EXTRA_INTERRUPT = "interrupt"
  const val EXTRA_RATE = "rate"
  const val EXTRA_PITCH = "pitch"
  const val EXTRA_VOLUME = "volume"

  const val EXTRA_PAUSE = "pause"

  const val PREF_ENABLED = "pref_speech_control"

  private const val MIN_SCALE = 0.25f
  private const val MAX_SCALE = 4f

  fun isAllowed(context: Context): Boolean =
    SharedPreferencesUtils.getSharedPreferences(context).getBoolean(PREF_ENABLED, false)

  fun clampScale(value: Float): Float =
    if (value.isNaN()) 1f else value.coerceIn(MIN_SCALE, MAX_SCALE)

  fun clampVolume(value: Float): Float = if (value.isNaN()) 1f else value.coerceIn(0f, 1f)

  @MainThread
  fun speak(
    text: CharSequence,
    interrupt: Boolean,
    rate: Float = 1f,
    pitch: Float = 1f,
    volume: Float = 1f,
  ): Boolean {
    if (text.isBlank()) return false
    val feedback = activeService()?.feedbackReturner ?: return false
    val params =
      Bundle().apply {
        putFloat(SpeechParam.RATE, clampScale(rate))
        putFloat(SpeechParam.PITCH, clampScale(pitch))
        putFloat(SpeechParam.VOLUME, clampVolume(volume))
      }
    val options =
      SpeakOptions.create()
        .setQueueMode(if (interrupt) QUEUE_MODE_INTERRUPT else QUEUE_MODE_QUEUE)
        .setFlags(FLAG_PROSODY_FROM_CALLER or FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE)
        .setSpeechParams(params)
    return feedback.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, options))
  }

  @MainThread
  fun stop(): Boolean {
    val service = activeService() ?: return false
    service.interruptAllFeedback(false)
    return true
  }

  @MainThread
  fun pause(pause: Boolean): Boolean {
    val service = activeService() ?: return false
    val feedback = service.feedbackReturner ?: return false
    if (service.speechController.readyToPause() != pause) return false
    if (pause) {
      if (service.actorState.continuousRead.isActive) {
        service.interruptAllFeedback(false)
      } else {
        service.interruptFullScreenReadActor()
      }
    }
    return feedback.returnFeedback(
      EVENT_ID_UNTRACKED,
      Feedback.speech(Feedback.Speech.Action.PAUSE_OR_RESUME),
    )
  }

  @MainThread fun isSpeaking(): Boolean = activeService()?.speechController?.isSpeaking == true

  private fun activeService(): TalkBackService? =
    TalkBackService.getInstance()?.takeIf {
      TalkBackService.isServiceActive() && !PauseController.isPaused()
    }
}
