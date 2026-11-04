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

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.feedbackpolicy.NotificationPopUpPane.PaneWindow
import com.google.android.accessibility.utils.input.WindowEventInterpreter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPopUpPaneTest {
  private val popUp = PaneWindow(SYSTEM_UI, isActive = false, showsNotification = { true })

  private fun paneAppeared(windowId: Int = SHADE, mainWindowsChanged: Boolean = false) =
    WindowEventInterpreter.EventInterpretation().apply {
      setEventType(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
      setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED)
      setMainWindowsChanged(mainWindowsChanged)
      eventSourceWindow.setId(windowId)
    }

  private fun isPopUp(
    interpretation: WindowEventInterpreter.EventInterpretation,
    window: PaneWindow?,
  ) = NotificationPopUpPane.isPopUp(interpretation, setOf(SYSTEM_UI)) { id ->
    if (id == SHADE) window else null
  }

  @Test
  fun notificationPoppingUpIsQuiet() {
    assertTrue(isPopUp(paneAppeared(), popUp))
  }

  @Test
  fun openingTheShadeIsSpoken() {
    assertFalse(isPopUp(paneAppeared(mainWindowsChanged = true), popUp))
    assertFalse(isPopUp(paneAppeared(), PaneWindow(SYSTEM_UI, isActive = true) { true }))
  }

  @Test
  fun systemUiPaneWithoutNotificationIsSpoken() {
    assertFalse(isPopUp(paneAppeared(), PaneWindow(SYSTEM_UI, isActive = false) { false }))
  }

  @Test
  fun otherAppsAndUnknownWindowsAreSpoken() {
    assertFalse(isPopUp(paneAppeared(), PaneWindow("example.app", isActive = false) { true }))
    assertFalse(isPopUp(paneAppeared(), PaneWindow(null, isActive = false) { true }))
    assertFalse(isPopUp(paneAppeared(), window = null))
    assertFalse(isPopUp(paneAppeared(windowId = OTHER), popUp))
  }

  @Test
  fun otherWindowChangesAreSpoken() {
    val windowsChanged = WindowEventInterpreter.EventInterpretation().apply {
      setEventType(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
      eventSourceWindow.setId(SHADE)
    }
    assertFalse(isPopUp(windowsChanged, popUp))
  }

  private companion object {
    const val SYSTEM_UI = "com.android.systemui"
    const val SHADE = 503
    const val OTHER = 10
  }
}
