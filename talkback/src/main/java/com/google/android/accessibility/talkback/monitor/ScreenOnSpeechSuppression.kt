/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.monitor

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.UserInterface.UserInputEventListener
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.monitor.DisplayMonitor.DisplayStateChangedListener

/**
 * A wake can produce several window changes, announcements, and focus events. Keep automatic
 * feedback quiet throughout that sequence, until the user starts interacting. This does not mute
 * the speech controller: selected status, unlock speech, and notifications use their normal paths.
 */
class ScreenOnSpeechSuppression(
  private val prefs: SharedPreferences,
  initiallyScreenOn: Boolean,
  private val isWear: Boolean = false,
) : DisplayStateChangedListener, RingerModeAndScreenMonitor.ScreenChangedListener,
  UserInputEventListener {
  private var screenOn = initiallyScreenOn
  private var waitingForInteraction = false

  override fun onDisplayStateChanged(displayOn: Boolean) {
    if (displayOn != screenOn) {
      screenOn = displayOn
      waitingForInteraction = displayOn
    }
  }

  override fun onScreenChanged(isInteractive: Boolean, eventId: EventId?) {
    onDisplayStateChanged(isInteractive)
  }

  fun isSuppressing(): Boolean =
    !isWear && waitingForInteraction &&
      ScreenAnnouncementSettings.isOn(prefs, ScreenAnnouncementSettings.SUPPRESS_EXTRA_SCREEN_ON_SPEECH)

  fun onUserInteraction() {
    waitingForInteraction = false
  }

  override fun touchInteractionState(active: Boolean) {
    if (active) onUserInteraction()
  }

  fun onKeyEvent(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_DOWN &&
      event.keyCode != KeyEvent.KEYCODE_POWER &&
      event.keyCode != KeyEvent.KEYCODE_WAKEUP &&
      event.keyCode != KeyEvent.KEYCODE_SLEEP) {
      onUserInteraction()
    }
  }

  fun onFocusAction(info: FocusActionInfo) {
    when (info.sourceAction) {
      FocusActionInfo.LOGICAL_NAVIGATION, FocusActionInfo.TOUCH_EXPLORATION,
      FocusActionInfo.MANUAL_SCROLL, FocusActionInfo.KEYBOARD_SHORTCUT_REFOCUS -> onUserInteraction()
    }
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    when (event.eventType) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> onUserInteraction()
    }
  }

  /** Focus can arrive without Backtalk's action history; System UI also posts later live updates. */
  fun shouldSuppressEvent(event: AccessibilityEvent): Boolean {
    if (!isSuppressing()) return false
    return when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
      AccessibilityEvent.TYPE_ANNOUNCEMENT,
      AccessibilityEvent.TYPE_VIEW_SELECTED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> event.packageName?.toString() == "com.android.systemui"
      else -> false
    }
  }

  companion object {
    /** All speech paths share the service's wake state, rather than independent one-shot flags. */
    @JvmStatic
    fun from(context: Context): ScreenOnSpeechSuppression? =
      (context as? TalkBackService)?.screenOnSpeechSuppression

    @JvmStatic
    fun isSuppressing(context: Context): Boolean = from(context)?.isSuppressing() == true
  }
}
