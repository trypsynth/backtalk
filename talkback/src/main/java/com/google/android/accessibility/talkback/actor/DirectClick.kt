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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.function.BooleanSupplier
import kotlin.math.abs

/**
 * Clicks a node by touching it on the screen, as a finger would, for controls that do nothing on
 * ACTION_CLICK and react only to touches, such as "Add to favorites" in TikTok, which works out the
 * button from where the finger lands.
 *
 * A gesture Backtalk dispatches goes through touch exploration like a finger does, so its press
 * reaches gesture detection rather than the app, also in the 300 ms after a double tap while
 * Android still counts the touch as going on. Gesture detection takes the press with [claim] and
 * asks Android to hand it to the app, and only then is the finger lifted: the press is a stroke
 * that stays down, and the lift continues it. Android takes the two requests in the order Backtalk
 * sends them, so the app gets the press before the lift, and holds it only for as long as the lift
 * takes, however busy Backtalk is. Were the lift sent with the press, it could come first, and the
 * app would keep a finger down for a long press.
 *
 * Everything runs on the main thread. At most one press is in flight, and whatever ends it, be it
 * the lift completing or being cancelled, a timeout or [shutdown], goes through [endPress], which
 * leaves nothing behind. A press that doesn't reach the app is clicked the usual way.
 */
class DirectClick
@VisibleForTesting
internal constructor(
  /** Where to touch the node, or null if it can't be touched. */
  private val locate: (AccessibilityNodeInfoCompat) -> Point?,
  /** Dispatches the gesture, calling back on [handler]. Returns false if Android refused it. */
  private val dispatch: (GestureDescription, GestureResultCallback) -> Boolean,
  /**
   * Whether gesture detection would get a press now and could hand it to the app. Not while a
   * finger is down, whose touch the press would cancel, nor while Android takes a new touch for
   * more touch exploration.
   */
  private val readyToPress: BooleanSupplier,
  private val passThrough: PassThrough,
  private val clickTheUsualWay: Click,
  private val handler: Handler,
) {

  /** Clicks a node the usual way, with ACTION_CLICK. */
  fun interface Click {
    fun click(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean
  }

  /** Tells whether Android passes a touch at a point straight to the app. */
  fun interface PassThrough {
    fun passesThrough(x: Int, y: Int): Boolean
  }

  private class Press(
    val node: AccessibilityNodeInfoCompat,
    val eventId: EventId?,
    val point: Point,
    val stroke: StrokeDescription,
  ) {
    // When the press went down, as gesture detection got it, or 0 until then. Every event of the
    // press carries it.
    var downTime = 0L
    var lifting = false
    var handedOver = false
  }

  // The press in flight, or null.
  private var press: Press? = null
  // The latest press, kept after it ends, so that its late events still don't count as a finger.
  private var latest: Press? = null

  private val pressTimedOut = Runnable {
    val press = press ?: return@Runnable
    LogUtils.w(TAG, "Gesture detection did not get the press")
    lift(press)
  }

  private val liftTimedOut = Runnable {
    val press = press ?: return@Runnable
    LogUtils.w(TAG, "Android reported nothing about the lift")
    onLiftEnded(press)
  }

  /**
   * Clicks [node] by touching it. Returns false if it can't be touched now, so that the caller
   * clicks it the usual way.
   */
  @MainThread
  fun click(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
    if (press != null || !readyToPress.asBoolean) {
      return false
    }
    val point = locate(node) ?: return false
    // Android would hand a press there to the app at once, so the lift would wait on Backtalk.
    if (passThrough.passesThrough(point.x, point.y)) {
      return false
    }
    val stroke = StrokeDescription(pathTo(point), 0, 1, /* willContinue= */ true)
    val press = Press(node, eventId, point, stroke)
    this.press = press
    latest = press
    val callback =
      object : GestureResultCallback() {
        // The finger stays down until the lift.
        override fun onCompleted(gestureDescription: GestureDescription) {}

        override fun onCancelled(gestureDescription: GestureDescription) = onPressCancelled(press)
      }
    if (!dispatch(gestureOf(stroke), callback)) {
      LogUtils.w(TAG, "Android refused the press")
      endPress()
      return false
    }
    LogUtils.d(TAG, "Pressing (%d, %d)", point.x, point.y)
    handler.postDelayed(pressTimedOut, MAX_WAIT_FOR_PRESS_MS)
    return true
  }

  /**
   * Takes [event] if it belongs to the press of a direct click, which gesture detection must leave
   * alone. When the press goes down, [handOver] passes it to the app and returns whether the app
   * gets it, and then the finger is lifted.
   */
  @MainThread
  fun claim(event: MotionEvent, handOver: BooleanSupplier): Boolean {
    val latest = latest ?: return false
    if (!isPartOf(event, latest)) {
      return false
    }
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      latest.downTime = event.downTime
      if (latest === press && !latest.lifting) {
        latest.handedOver = handOver.asBoolean
        lift(latest)
      }
    }
    return true
  }

  /** Lifts a press still down, without clicking. */
  @MainThread
  fun shutdown() {
    val press = press ?: return
    endPress()
    latest = null
    if (!press.lifting) {
      dispatch(gestureOf(liftOf(press)), object : GestureResultCallback() {})
    }
  }

  private fun lift(press: Press) {
    press.lifting = true
    handler.removeCallbacks(pressTimedOut)
    val callback =
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) = onLiftEnded(press)

        override fun onCancelled(gestureDescription: GestureDescription) = onLiftEnded(press)
      }
    if (!dispatch(gestureOf(liftOf(press)), callback)) {
      // Android cancels the finger when the next touch or gesture starts.
      LogUtils.w(TAG, "Android refused the lift")
      onLiftEnded(press)
      return
    }
    handler.postDelayed(liftTimedOut, TAP_MS + MAX_WAIT_FOR_RESULT_MS)
  }

  private fun onLiftEnded(press: Press) {
    if (press !== this.press) {
      return
    }
    endPress()
    if (press.handedOver) {
      LogUtils.d(TAG, "Touched (%d, %d)", press.point.x, press.point.y)
    } else {
      LogUtils.w(TAG, "The press did not reach the app, so clicking the usual way")
      clickTheUsualWay.click(press.node, press.eventId)
    }
  }

  /** Android cancelled the press before it went down, as when a finger touched the screen. */
  private fun onPressCancelled(press: Press) {
    if (press !== this.press || press.lifting) {
      return
    }
    endPress()
    LogUtils.w(TAG, "Android cancelled the press, so clicking the usual way")
    clickTheUsualWay.click(press.node, press.eventId)
  }

  private fun endPress() {
    press = null
    handler.removeCallbacks(pressTimedOut)
    handler.removeCallbacks(liftTimedOut)
  }

  companion object {
    private const val TAG = "DirectClick"

    /** As long as a finger's tap, and far from a long press. */
    private val TAP_MS = ViewConfiguration.getTapTimeout().toLong()

    /** Gesture detection gets the press within milliseconds, and a busy main thread adds to it. */
    private const val MAX_WAIT_FOR_PRESS_MS = 500L

    /** Android reports the result as soon as the lift ends, so this only guards against none. */
    private const val MAX_WAIT_FOR_RESULT_MS = 1000L

    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun create(
      service: AccessibilityService,
      readyToPress: BooleanSupplier,
      passThrough: PassThrough,
      clickTheUsualWay: Click,
    ): DirectClick {
      val handler = Handler(Looper.getMainLooper())
      return DirectClick(
        locate = { node -> screenPoint(service, node) },
        dispatch = { gesture, callback -> service.dispatchGesture(gesture, callback, handler) },
        readyToPress = readyToPress,
        passThrough = passThrough,
        clickTheUsualWay = clickTheUsualWay,
        handler = handler,
      )
    }

    /**
     * Whether [event] belongs to [press]. Android gives the events of a gesture no device, and
     * each of them carries the down time of the first.
     */
    private fun isPartOf(event: MotionEvent, press: Press): Boolean {
      if (event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD || event.pointerCount != 1) {
        return false
      }
      if (event.actionMasked != MotionEvent.ACTION_DOWN) {
        return press.downTime != 0L && event.downTime == press.downTime
      }
      return press.downTime == 0L &&
        abs(event.x - press.point.x) < 1f &&
        abs(event.y - press.point.y) < 1f
    }

    private fun pathTo(point: Point) =
      Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }

    private fun gestureOf(stroke: StrokeDescription) =
      GestureDescription.Builder().addStroke(stroke).build()

    private fun liftOf(press: Press) =
      press.stroke.continueStroke(pathTo(press.point), 0, TAP_MS, /* willContinue= */ false)

    /**
     * Where to touch [node] on the screen, or null if a touch would not do what clicking it does:
     * it isn't on the screen, or it is a text field or a slider, where a touch puts the cursor or
     * sets the value where it lands, or the screen is magnified, so that the node is not where its
     * bounds say.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun screenPoint(
      service: AccessibilityService,
      node: AccessibilityNodeInfoCompat,
    ): Point? {
      if (node.isEditable || node.rangeInfo != null) {
        return null
      }
      val magnification = service.magnificationController.magnificationConfig
      if (magnification != null && magnification.scale > 1f) {
        return null
      }
      // Gesture detection hands touches to the app on the main display only.
      val window = node.unwrap().window ?: return null
      if (window.displayId != Display.DEFAULT_DISPLAY) {
        return null
      }
      val bounds = Rect().also { node.getBoundsInScreen(it) }
      // The part of the window that windows above it leave uncovered.
      val shown = Region().also { window.getRegionInScreen(it) }
      return pointIn(bounds, shown)
    }

    /**
     * The middle of [bounds] if [shown] holds it, or else the middle of the largest rectangle of
     * [bounds] that [shown] holds, or null if none of it is shown.
     */
    @VisibleForTesting
    @JvmStatic
    internal fun pointIn(bounds: Rect, shown: Region): Point? {
      if (bounds.isEmpty) {
        return null
      }
      if (shown.contains(bounds.centerX(), bounds.centerY())) {
        return Point(bounds.centerX(), bounds.centerY())
      }
      val visible = Region(shown)
      if (!visible.op(bounds, Region.Op.INTERSECT)) {
        return null
      }
      // What shows can be several rectangles, such as when a window above covers the middle.
      var largest: Rect? = null
      val part = Rect()
      val parts = RegionIterator(visible)
      while (parts.next(part)) {
        if (largest == null || area(part) > area(largest)) {
          largest = Rect(part)
        }
      }
      return largest?.let { Point(it.centerX(), it.centerY()) }
    }

    private fun area(rect: Rect): Long = rect.width().toLong() * rect.height()
  }
}
