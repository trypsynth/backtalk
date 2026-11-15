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

package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT
import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppGesturesTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  private val prefs
    get() = SharedPreferencesUtils.getSharedPreferences(context)

  private lateinit var mapping: GestureShortcutMapping

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    mapping = GestureShortcutMapping(context)
  }

  @After
  fun tearDown() {
    mapping.onUnbind()
    AppGestures.setFocusedPackageForTesting(null)
  }

  private fun setForApp(packageName: String, action: Int) {
    prefs
      .edit()
      .putString(
        AppGestures.prefKey(context.getString(R.string.pref_shortcut_right_key), packageName),
        context.getString(action),
      )
      .commit()
  }

  private fun focusIn(packageName: String) = AppGestures.setFocusedPackageForTesting(packageName)

  private fun swipeRight() = mapping.getActionKeyFromGestureId(GESTURE_SWIPE_RIGHT)

  @Test
  fun gestureSetForAnAppWorksOnlyInThatApp() {
    val usual = swipeRight()
    setForApp("com.example.reader", R.string.shortcut_value_read_from_top)

    focusIn("com.example.reader")
    assertEquals(context.getString(R.string.shortcut_value_read_from_top), swipeRight())

    focusIn("com.example.other")
    assertEquals(usual, swipeRight())
  }

  @Test
  fun otherGesturesInTheAppKeepTheirAction() {
    val usualLeft = mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT)
    setForApp("com.example.reader", R.string.shortcut_value_read_from_top)

    focusIn("com.example.reader")
    assertEquals(usualLeft, mapping.getActionKeyFromGestureId(GESTURE_SWIPE_LEFT))
  }

  @Test
  fun appWithNoGestureCanStillBeUnassigned() {
    setForApp("com.example.game", R.string.shortcut_value_unassigned)

    focusIn("com.example.game")
    assertEquals(context.getString(R.string.shortcut_value_unassigned), swipeRight())
  }

  @Test
  fun listsAndRemovesTheAppsWithGestures() {
    setForApp("com.example.reader", R.string.shortcut_value_read_from_top)
    setForApp("com.example.game", R.string.shortcut_value_unassigned)
    assertEquals(
      setOf("com.example.game", "com.example.reader"),
      AppGestures.packagesWithGestures(prefs),
    )

    AppGestures.removeAll(prefs, "com.example.game")
    assertEquals(setOf("com.example.reader"), AppGestures.packagesWithGestures(prefs))
  }

  @Test
  fun backtalkItselfNeverGetsGesturesOfItsOwn() {
    assertFalse(AppGestures.canSetGesturesFor(context, context.packageName))
  }
}
