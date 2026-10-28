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

package com.google.android.accessibility.talkback.actor

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.time.Duration
import java.util.IdentityHashMap
import java.util.function.BooleanSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs a direct click with the screen, Android's gesture dispatch and gesture detection replaced by
 * recordings. The main looper is paused, so each test decides when time passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectClickTest {
  private val node = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo())
  private val otherNode = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo())

  // Where the screen says each node is, or null if it can't be touched. By identity, as two nodes
  // with no source are equal.
  private val points =
    IdentityHashMap<AccessibilityNodeInfoCompat, Point?>().apply {
      put(node, Point(300, 500))
      put(otherNode, Point(10, 20))
    }
  private val gestures = mutableListOf<GestureDescription>()
  private val callbacks = mutableListOf<GestureResultCallback>()
  private var androidAcceptsGestures = true
  private var readyToPress = true
  // The points where Android passes a touch straight to the app.
  private val passingThrough = mutableSetOf<Point>()
  private val usualClicks = mutableListOf<AccessibilityNodeInfoCompat>()

  // Gesture detection, which hands the press to the app if it can.
  private var appGetsThePress = true
  private var handOvers = 0
  private val handOver = BooleanSupplier {
    handOvers++
    appGetsThePress
  }

  private val directClick =
    DirectClick(
      locate = { node -> points[node] },
      dispatch = { gesture, callback ->
        gestures.add(gesture)
        callbacks.add(callback)
        androidAcceptsGestures
      },
      readyToPress = { readyToPress },
      passThrough = { x, y -> Point(x, y) in passingThrough },
      clickTheUsualWay = { node, _ ->
        usualClicks.add(node)
        true
      },
      handler = Handler(Looper.getMainLooper()),
    )

  private val tapMs = ViewConfiguration.getTapTimeout().toLong()

  private fun pass(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  }

  private fun stroke(gesture: Int): StrokeDescription = gestures[gesture].getStroke(0)

  private fun startOf(stroke: StrokeDescription): Point {
    val bounds = RectF()
    stroke.path.computeBounds(bounds, /* exact= */ true)
    return Point(bounds.left.toInt(), bounds.top.toInt())
  }

  private fun idOf(stroke: StrokeDescription, getter: String) =
    StrokeDescription::class.java.getMethod(getter).invoke(stroke) as Int

  /** An event as gesture detection gets it, of a gesture Backtalk dispatched unless [deviceId]. */
  private fun event(
    action: Int,
    x: Float,
    y: Float,
    downTime: Long,
    deviceId: Int = KeyCharacterMap.VIRTUAL_KEYBOARD,
  ): MotionEvent {
    val properties =
      MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_UNKNOWN
      }
    val coords =
      MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
      }
    return MotionEvent.obtain(
      downTime,
      if (action == ACTION_DOWN) downTime else downTime + tapMs,
      action,
      1,
      arrayOf(properties),
      arrayOf(coords),
      0,
      0,
      1f,
      1f,
      deviceId,
      0,
      InputDevice.SOURCE_TOUCHSCREEN,
      0,
    )
  }

  /** Gesture detection gets the press of the node at (300, 500) going down. */
  private fun pressGoesDown(downTime: Long = 1000L) =
    directClick.claim(event(ACTION_DOWN, 300f, 500f, downTime), handOver)

  @Test
  fun aClickPressesTheNodeAndKeepsTheFingerDown() {
    assertTrue(directClick.click(node, null))

    assertEquals(1, gestures.size)
    assertTrue(stroke(0).willContinue())
    assertEquals(0L, stroke(0).startTime)
    assertEquals(Point(300, 500), startOf(stroke(0)))
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
  }

  @Test
  fun thePressIsHandedToTheAppWhenGestureDetectionGetsItAndThenLifted() {
    directClick.click(node, null)
    callbacks[0].onCompleted(gestures[0])
    assertEquals(1, gestures.size)

    assertTrue(pressGoesDown())

    assertEquals(1, handOvers)
    assertEquals(2, gestures.size)
    assertFalse(stroke(1).willContinue())
    assertEquals(tapMs, stroke(1).duration)
    assertEquals(Point(300, 500), startOf(stroke(1)))
    assertEquals(idOf(stroke(0), "getId"), idOf(stroke(1), "getContinuedStrokeId"))

    callbacks[1].onCompleted(gestures[1])
    pass(5000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
  }

  @Test
  fun theLateEventsOfAPressAreTakenToo() {
    directClick.click(node, null)
    pressGoesDown(downTime = 1000L)
    callbacks[1].onCompleted(gestures[1])

    // Gesture detection gets the lift after Android reported it, when the app didn't get it.
    assertTrue(directClick.claim(event(ACTION_UP, 300f, 500f, 1000L), handOver))
    assertEquals(1, handOvers)
  }

  @Test
  fun aFingerIsNotTaken() {
    directClick.click(node, null)

    assertFalse(directClick.claim(event(ACTION_DOWN, 300f, 500f, 1000L, deviceId = 3), handOver))
    assertEquals(0, handOvers)
    assertEquals(1, gestures.size)
  }

  @Test
  fun anotherGestureIsNotTaken() {
    directClick.click(node, null)
    pressGoesDown(downTime = 1000L)

    assertFalse(directClick.claim(event(ACTION_DOWN, 40f, 60f, 1200L), handOver))
    assertFalse(directClick.claim(event(ACTION_UP, 40f, 60f, 1200L), handOver))
    assertEquals(1, handOvers)
  }

  @Test
  fun aPressTheAppDoesNotGetIsLiftedAndClickedTheUsualWay() {
    appGetsThePress = false
    directClick.click(node, null)
    pressGoesDown()

    assertEquals(2, gestures.size)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
    callbacks[1].onCompleted(gestures[1])
    assertEquals(listOf(node), usualClicks)
  }

  @Test
  fun aPressGestureDetectionDoesNotGetIsLiftedAfterTheTimeoutAndClickedTheUsualWay() {
    directClick.click(node, null)
    pass(499)
    assertEquals(1, gestures.size)
    pass(1)
    assertEquals(2, gestures.size)
    assertFalse(stroke(1).willContinue())

    callbacks[1].onCompleted(gestures[1])
    assertEquals(listOf(node), usualClicks)

    // The press reaches gesture detection late after all: it is still no finger, but too late to
    // hand over.
    assertTrue(pressGoesDown())
    assertEquals(0, handOvers)
    assertEquals(2, gestures.size)
  }

  @Test
  fun aPressAndroidCancelsBeforeItGoesDownIsClickedTheUsualWay() {
    directClick.click(node, null)
    callbacks[0].onCancelled(gestures[0])

    assertEquals(listOf(node), usualClicks)
    pass(5000)
    assertEquals(1, gestures.size)
    assertEquals(listOf(node), usualClicks)
  }

  @Test
  fun aLiftAndroidCancelsAfterTheHandOverClicksNothingMore() {
    // A finger touches the screen during the press. Android still lifts it for the app.
    directClick.click(node, null)
    pressGoesDown()
    callbacks[1].onCancelled(gestures[1])

    pass(5000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
  }

  @Test
  fun aLiftAndroidRefusesIsClickedTheUsualWayIfTheAppDidNotGetThePress() {
    appGetsThePress = false
    directClick.click(node, null)
    androidAcceptsGestures = false
    pressGoesDown()

    assertEquals(listOf(node), usualClicks)
  }

  @Test
  fun aLiftAndroidRefusesAfterTheHandOverClicksNothingMore() {
    directClick.click(node, null)
    androidAcceptsGestures = false
    pressGoesDown()

    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
    // Nothing is left in flight.
    androidAcceptsGestures = true
    assertTrue(directClick.click(node, null))
  }

  @Test
  fun aLiftAndroidNeverReportsOnEndsAfterTheTimeout() {
    directClick.click(node, null)
    pressGoesDown()
    assertFalse(directClick.click(otherNode, null))

    pass(tapMs + 999)
    assertFalse(directClick.click(otherNode, null))
    pass(1)
    assertTrue(directClick.click(otherNode, null))
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
  }

  @Test
  fun aClickDuringAPressIsLeftToTheCaller() {
    assertTrue(directClick.click(node, null))

    assertFalse(directClick.click(otherNode, null))
    assertEquals(1, gestures.size)
  }

  @Test
  fun noPressWhileGestureDetectionIsNotReady() {
    // Such as while a finger is down, or Android takes a new touch for more touch exploration.
    readyToPress = false

    assertFalse(directClick.click(node, null))
    assertEquals(listOf<GestureDescription>(), gestures)
  }

  @Test
  fun aNodeWhereAndroidPassesTouchesToTheAppIsLeftToTheCaller() {
    passingThrough.add(Point(300, 500))

    assertFalse(directClick.click(node, null))
    assertEquals(listOf<GestureDescription>(), gestures)
  }

  @Test
  fun aNodeThatCanNotBeTouchedIsLeftToTheCaller() {
    points[node] = null

    assertFalse(directClick.click(node, null))
    assertEquals(listOf<GestureDescription>(), gestures)
  }

  @Test
  fun aPressAndroidRefusesIsLeftToTheCallerAndLeavesNothingInFlight() {
    androidAcceptsGestures = false
    assertFalse(directClick.click(node, null))
    pass(5000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)

    androidAcceptsGestures = true
    assertTrue(directClick.click(node, null))
  }

  @Test
  fun shutdownLiftsAPressStillDownWithoutClicking() {
    directClick.click(node, null)
    directClick.shutdown()

    assertEquals(2, gestures.size)
    assertFalse(stroke(1).willContinue())
    callbacks[1].onCompleted(gestures[1])
    pass(5000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
    // Its press is no longer told apart.
    assertFalse(pressGoesDown())
  }

  @Test
  fun shutdownLeavesALiftInFlightToAndroid() {
    directClick.click(node, null)
    pressGoesDown()
    directClick.shutdown()

    assertEquals(2, gestures.size)
    callbacks[1].onCompleted(gestures[1])
    pass(5000)
    assertEquals(listOf<AccessibilityNodeInfoCompat>(), usualClicks)
  }

  @Test
  fun theMiddleIsTouchedWhenItShows() {
    assertEquals(
      Point(50, 25),
      DirectClick.pointIn(Rect(0, 0, 100, 50), Region(0, 0, 1080, 2400)),
    )
  }

  @Test
  fun aCoveredMiddleMovesTheTouchToTheLargestPartThatShows() {
    // A window above covers the node from x 80 to 200, leaving 80 pixels on the left and 100 on
    // the right.
    val shown = Region(0, 0, 1080, 2400)
    shown.op(Rect(80, 0, 200, 100), Region.Op.DIFFERENCE)

    assertEquals(Point(250, 50), DirectClick.pointIn(Rect(0, 0, 300, 100), shown))
  }

  @Test
  fun aNodeThatDoesNotShowCanNotBeTouched() {
    assertNull(DirectClick.pointIn(Rect(0, 0, 100, 100), Region(0, 500, 1080, 2400)))
  }

  @Test
  fun aNodeWithNoSizeCanNotBeTouched() {
    assertNull(DirectClick.pointIn(Rect(10, 10, 10, 10), Region(0, 0, 1080, 2400)))
  }
}
