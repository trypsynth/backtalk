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

package com.google.android.accessibility.utils.gestures

import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeAndHoldTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val pixelsPerCm = context.resources.displayMetrics.xdpi / 2.54f
  private val completed = mutableListOf<Int>()
  private var assigned = true
  private val config =
    object : GestureConfigProvider {
      override fun isSwipeAndHoldAssigned(gestureId: Int) = assigned
    }
  private val downTime = SystemClock.uptimeMillis()
  private var x = 5 * pixelsPerCm
  private var y = 10 * pixelsPerCm

  private fun swipe(gestureId: Int, hold: Boolean, vararg directions: Int) =
    Swipe(
      context,
      directions,
      gestureId,
      hold,
      { id, state, _ -> if (state == GestureMatcher.STATE_GESTURE_COMPLETED) completed += id },
      config,
      {},
    )

  private fun send(matchers: List<Swipe>, action: Int) {
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
    matchers.forEach { it.onMotionEvent(null, event) }
    event.recycle()
  }

  @After
  fun restoreHoldTime() {
    GestureConfiguration.setSwipeHoldTimeoutMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS)
  }

  private fun waitMs(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

  private fun move(matchers: List<Swipe>, dxCm: Float, dyCm: Float) {
    repeat(4) {
      waitMs(10)
      x += dxCm * pixelsPerCm / 4
      y += dyCm * pixelsPerCm / 4
      send(matchers, MotionEvent.ACTION_MOVE)
    }
  }

  @Test
  fun swipeRightThenRest_completesWhileTheFingerIsDown() {
    val matchers = listOf(swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS - 1L)
    assertTrue(completed.isEmpty())
    waitMs(2)
    assertEquals(listOf(HOLD_RIGHT), completed)
  }

  @Test
  fun shorterHoldTime_completesSooner() {
    GestureConfiguration.setSwipeHoldTimeoutMs(100)
    val matchers = listOf(swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    waitMs(99)
    assertTrue(completed.isEmpty())
    waitMs(2)
    assertEquals(listOf(HOLD_RIGHT), completed)
  }

  @Test
  fun shorterHoldTime_stillLetsAQuickSwipeLift() {
    GestureConfiguration.setSwipeHoldTimeoutMs(100)
    val matchers =
      listOf(swipe(GESTURE_SWIPE_RIGHT, false, Swipe.RIGHT), swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    waitMs(50)
    send(matchers, MotionEvent.ACTION_UP)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT), completed)
  }

  @Test
  fun swipeRightThenLiftBeforeTheHold_isAnOrdinarySwipe() {
    val matchers =
      listOf(swipe(GESTURE_SWIPE_RIGHT, false, Swipe.RIGHT), swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS - 50L)
    send(matchers, MotionEvent.ACTION_UP)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT), completed)
  }

  @Test
  fun movingAgain_startsTheWaitAgain() {
    val matchers = listOf(swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS - 50L)
    move(matchers, 1f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS - 50L)
    assertTrue(completed.isEmpty())
    waitMs(60)
    assertEquals(listOf(HOLD_RIGHT), completed)
  }

  @Test
  fun leftThenUpThenRest_completesOnlyTheAngleHold() {
    val matchers =
      listOf(swipe(HOLD_LEFT_UP, true, Swipe.LEFT, Swipe.UP), swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, -2f, 0f)
    move(matchers, 0f, -2f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS + 1L)
    assertEquals(listOf(HOLD_LEFT_UP), completed)
  }

  @Test
  fun rightThenLeftThenRest_isNotARightHold() {
    val matchers = listOf(swipe(HOLD_RIGHT, true, Swipe.RIGHT))
    send(matchers, MotionEvent.ACTION_DOWN)
    move(matchers, 2f, 0f)
    move(matchers, -2f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS * 2L)
    assertTrue(completed.isEmpty())
  }

  @Test
  fun unassignedHold_isNotMatched() {
    assigned = false
    val hold = swipe(HOLD_RIGHT, true, Swipe.RIGHT)
    send(listOf(hold), MotionEvent.ACTION_DOWN)
    assertEquals(GestureMatcher.STATE_GESTURE_CANCELED, hold.state)
    move(listOf(hold), 2f, 0f)
    waitMs(GestureConfiguration.DEFAULT_SWIPE_HOLD_TIMEOUT_MS * 2L)
    assertFalse(completed.contains(HOLD_RIGHT))
  }

  private companion object {
    const val HOLD_RIGHT = GestureManifold.GESTURE_SWIPE_RIGHT_THEN_HOLD
    const val HOLD_LEFT_UP = GestureManifold.GESTURE_SWIPE_LEFT_AND_UP_THEN_HOLD
  }
}
