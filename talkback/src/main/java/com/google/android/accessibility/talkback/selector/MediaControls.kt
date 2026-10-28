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

package com.google.android.accessibility.talkback.selector

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.google.android.accessibility.talkback.R

/** Actions for the Media controls reading control, sent as media button events. */
internal enum class MediaAction(val keyCode: Int, val titleResId: Int) {
  MEDIA_PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.media_action_previous), // 88
  MEDIA_REWIND(KeyEvent.KEYCODE_MEDIA_REWIND, R.string.media_action_rewind), // 89
  MEDIA_PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.string.media_action_play_pause), // 85
  MEDIA_FAST_FORWARD(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, R.string.media_action_fast_forward), // 90
  MEDIA_NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, R.string.media_action_next), // 87
  MEDIA_STOP(KeyEvent.KEYCODE_MEDIA_STOP, R.string.media_action_stop), // 86
}

internal object MediaControls {
  /** Sends [action] as media button events, as a wired headset would. */
  @JvmStatic
  fun dispatch(context: Context, action: MediaAction): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, action.keyCode))
    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, action.keyCode))
    return true
  }
}