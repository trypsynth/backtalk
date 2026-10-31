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

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.google.android.accessibility.utils.gestures.GestureManifold.GestureConfigProvider
import com.google.common.collect.ImmutableList
import java.util.concurrent.TimeUnit
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
  private val manifold by lazy {
    GestureManifold(
      context,
      object : GestureManifold.Listener {
        override fun onGestureStarted(gestureId: Int) {}

        override fun onGestureCompleted(gestureEvent: AccessibilityGestureEvent) {
          completed += gestureEvent.gestureId
        }

        override fun onGestureCancelled(gestureId: Int) {}
      },
      config,
      {},
      0,
      ImmutableList.of(
        "MAPPER_GESTURE_SWIPE_RIGHT",
        "MAPPER_GESTURE_SWIPE_RIGHT_AND_LEFT",
        "MAPPER_GESTURE_SWIPE_RIGHT_THEN_HOLD",
        "MAPPER_GESTURE_SWIPE_LEFT_AND_UP_THEN_HOLD",
      ),
    )
  }
  private val downTime = SystemClock.uptimeMillis()
  private var x = 5 * pixelsPerCm
  private var y = 10 * pixelsPerCm

  private fun send(action: Int) {
    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
    manifold.onMotionEvent(null, event)
    event.recycle()
  }

  private fun waitMs(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

  private fun move(dxCm: Float, dyCm: Float) {
    repeat(4) {
      waitMs(10)
      x += dxCm * pixelsPerCm / 4
      y += dyCm * pixelsPerCm / 4
      send(MotionEvent.ACTION_MOVE)
    }
  }

  private fun rest(ms: Long) {
    waitMs(ms)
    send(MotionEvent.ACTION_MOVE)
  }

  @Test
  fun swipeRightThenRestThenLift_isRightHold() {
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS.toLong())
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(HOLD_RIGHT), completed)
  }

  @Test
  fun swipeRightThenRest_doesNothingUntilTheLift() {
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS * 4L)
    assertTrue(completed.isEmpty())
  }

  @Test
  fun swipeRightThenLiftBeforeTheHold_isAnOrdinarySwipe() {
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS - 50L)
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT), completed)
  }

  @Test
  fun rightThenPauseThenLeft_isRightAndLeft() {
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS + 50L)
    move(-2f, 0f)
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT_AND_LEFT), completed)
  }

  @Test
  fun movingAgain_startsTheRestAgain() {
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS - 50L)
    move(1f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS - 50L)
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(GESTURE_SWIPE_RIGHT), completed)
  }

  @Test
  fun leftThenUpThenRest_isOnlyTheAngleHold() {
    send(MotionEvent.ACTION_DOWN)
    move(-2f, 0f)
    move(0f, -2f)
    rest(Swipe.HOLD_TIMEOUT_MS + 1L)
    send(MotionEvent.ACTION_UP)
    assertEquals(listOf(HOLD_LEFT_UP), completed)
  }

  @Test
  fun unassignedHold_isAnOrdinarySwipe() {
    assigned = false
    send(MotionEvent.ACTION_DOWN)
    move(2f, 0f)
    rest(Swipe.HOLD_TIMEOUT_MS * 2L)
    send(MotionEvent.ACTION_UP)
    assertFalse(completed.contains(HOLD_RIGHT))
  }

  private companion object {
    const val HOLD_RIGHT = GestureManifold.GESTURE_SWIPE_RIGHT_THEN_HOLD
    const val HOLD_LEFT_UP = GestureManifold.GESTURE_SWIPE_LEFT_AND_UP_THEN_HOLD
  }
}
