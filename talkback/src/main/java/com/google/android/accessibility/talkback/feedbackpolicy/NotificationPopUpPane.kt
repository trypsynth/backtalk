/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.feedbackpolicy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.monitor.ScreenOnSpeechSuppression
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils
import com.google.android.accessibility.utils.input.WindowEventInterpreter

/**
 * A notification that pops up at the top of the screen shows System UI's notification window
 * without making it active, and System UI announces that window's pane, "Notification shade". The
 * notification's own event already decides whether the notification is spoken, for example by
 * Speak notifications or Do Not Disturb, so the pane title adds nothing. Opening the shade makes the
 * window active, and is still announced.
 */
object NotificationPopUpPane {
  /** What the check needs to know about the window that a pane appeared in. */
  internal class PaneWindow(
    val packageName: String?,
    val isActiveOrFocused: Boolean,
    val showsNotification: () -> Boolean,
  )

  /** Whether the pane that [interpretation] reports is a notification popping up. */
  @JvmStatic
  fun isPopUp(
    service: AccessibilityService,
    interpretation: WindowEventInterpreter.EventInterpretation,
  ): Boolean {
    val systemUiPackages = ScreenOnSpeechSuppression.from(service)?.systemUiPackages ?: return false
    return isPopUp(interpretation, systemUiPackages) { id -> paneWindow(service, id) }
  }

  internal fun isPopUp(
    interpretation: WindowEventInterpreter.EventInterpretation,
    systemUiPackages: Set<String>,
    window: (Int) -> PaneWindow?,
  ): Boolean {
    if (interpretation.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      interpretation.contentChangeTypes != AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED ||
      interpretation.mainWindowsChanged) {
      return false
    }
    // The event's own source names its package without asking the app, so other apps' panes are
    // let through before any window lookup.
    val sourcePackage = interpretation.sourceNode?.packageName?.toString()
    if (sourcePackage != null && sourcePackage !in systemUiPackages) return false
    val paneWindow = window(interpretation.eventSourceWindow.id) ?: return false
    return !paneWindow.isActiveOrFocused && paneWindow.packageName in systemUiPackages &&
      paneWindow.showsNotification()
  }

  private fun paneWindow(service: AccessibilityService, windowId: Int): PaneWindow? {
    val window = AccessibilityServiceCompatUtils.getWindowInfo(service, windowId) ?: return null
    val root = AccessibilityWindowInfoUtils.getRoot(window) ?: return null
    val packageName = root.packageName?.toString()
    return PaneWindow(packageName, window.isActive || window.isFocused) {
      root.findAccessibilityNodeInfosByViewId("$packageName:$NOTIFICATION_ROW_ID").isNotEmpty()
    }
  }

  /** System UI's view for one notification, as also found by HeadsUpNotificationEventInterpreter. */
  private const val NOTIFICATION_ROW_ID = "id/expandableNotificationRow"
}
