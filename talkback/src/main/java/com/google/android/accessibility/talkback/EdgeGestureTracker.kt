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

package com.google.android.accessibility.talkback

import android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN
import android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP
import android.content.SharedPreferences
import android.os.Handler
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.ViewConfiguration
import com.google.android.accessibility.talkback.flags.FeatureFlagReader
import com.google.android.accessibility.talkback.gesture.Edge
import com.google.android.accessibility.talkback.gesture.EdgeGesture
import com.google.android.accessibility.talkback.gesture.EdgeGestureSettings
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.gestures.GestureConfiguration
import com.google.android.accessibility.utils.gestures.GestureUtils
import kotlin.math.hypot

/**
 * Gives one-finger swipes up and down and triple taps that start on the left or right edge of the
 * screen the actions set for that edge. While edge gestures are off, it takes nothing.
 */
class EdgeGestureTracker(
  private val service: TalkBackService,
  private val prefs: SharedPreferences,
  private val handler: Handler,
  private val performDoubleTap: Runnable,
) {
  private enum class ThirdTap {
    NONE,
    WAITING,
    DOWN,
    // The third touch was no tap, so the double tap went ahead and the rest of the touch is ignored.
    SWALLOWING,
  }

  private val unassigned = service.getString(R.string.shortcut_value_unassigned)
  private val tapSlop = ViewConfiguration.get(service).scaledTouchSlop
  private val doubleTapSlop =
    (ViewConfiguration.get(service).scaledDoubleTapSlop *
        FeatureFlagReader.gestureDoubleTapSlopMultiplier(service))
      .toInt()

  private var touchEdge: Edge? = null
  private var downX = 0f
  private var downY = 0f
  private var thirdTap = ThirdTap.NONE
  private var waitingEdge: Edge? = null
  private var doubleTapX = 0f
  private var doubleTapY = 0f
  private var thirdTapDownTime = 0L
  private val doubleTapTimeout = Runnable { performWaitingDoubleTap() }

  /** Returns true if the event belongs to a third tap, so nothing else may handle it. */
  fun onMotionEvent(event: MotionEvent): Boolean {
    if (takeThirdTap(event)) {
      return true
    }
    if (event.actionMasked == ACTION_DOWN) {
      downX = event.x
      downY = event.y
      touchEdge = edgeAt(event.x, event.y)
    }
    return false
  }

  /**
   * Returns true if the gesture is taken: its edge action is performed, or a double tap waits for a
   * third tap.
   */
  fun onGesture(gestureId: Int, serviceHandlesDoubleTap: Boolean): Boolean {
    val edge = touchEdge ?: return false
    val gesture =
      when (gestureId) {
        GESTURE_SWIPE_UP -> EdgeGesture.SWIPE_UP
        GESTURE_SWIPE_DOWN -> EdgeGesture.SWIPE_DOWN
        GESTURE_DOUBLE_TAP -> if (serviceHandlesDoubleTap) EdgeGesture.TRIPLE_TAP else null
        else -> null
      } ?: return false
    val action = EdgeGestureSettings.action(prefs, edge, gesture, unassigned) ?: return false
    if (!service.canPerformEdgeAction()) {
      return false
    }
    if (gesture == EdgeGesture.TRIPLE_TAP) {
      waitForThirdTap(edge)
    } else {
      handler.post { service.performEdgeAction(action) }
    }
    return true
  }

  fun stop() {
    handler.removeCallbacks(doubleTapTimeout)
    thirdTap = ThirdTap.NONE
    waitingEdge = null
    touchEdge = null
  }

  private fun edgeAt(x: Float, y: Float): Edge? {
    if (!EdgeGestureSettings.isEnabled(prefs) || FormFactorUtils.isAndroidWear()) {
      return null
    }
    val metrics = service.resources.displayMetrics
    val edgeWidth = metrics.xdpi / GestureUtils.CM_PER_INCH * EdgeGestureSettings.widthCm(prefs)
    val edge = EdgeGestureSettings.edgeAt(x, metrics.widthPixels.toFloat(), edgeWidth)
    return if (edge == null || isOnKeyboard(x.toInt(), y.toInt())) null else edge
  }

  private fun isOnKeyboard(x: Int, y: Int): Boolean {
    val windowOnTop =
      service.windows
        ?.filter { AccessibilityWindowInfoUtils.getRegionInScreen(it)?.contains(x, y) == true }
        ?.maxByOrNull { it.layer }
    return AccessibilityWindowInfoUtils.isImeWindow(windowOnTop)
  }

  private fun waitForThirdTap(edge: Edge) {
    doubleTapX = downX
    doubleTapY = downY
    waitingEdge = edge
    thirdTap = ThirdTap.WAITING
    handler.removeCallbacks(doubleTapTimeout)
    handler.postDelayed(doubleTapTimeout, GestureConfiguration.getMultiTapTimeoutMs().toLong())
  }

  private fun performWaitingDoubleTap() {
    handler.removeCallbacks(doubleTapTimeout)
    if (waitingEdge == null) {
      return
    }
    waitingEdge = null
    if (thirdTap == ThirdTap.WAITING) {
      thirdTap = ThirdTap.NONE
    }
    performDoubleTap.run()
  }

  private fun distanceFromDoubleTap(event: MotionEvent): Double =
    hypot((event.x - doubleTapX).toDouble(), (event.y - doubleTapY).toDouble())

  private fun takeThirdTap(event: MotionEvent): Boolean {
    val action = event.actionMasked
    when (thirdTap) {
      ThirdTap.NONE -> return false
      ThirdTap.WAITING -> {
        if (action != ACTION_DOWN) {
          return false
        }
        if (distanceFromDoubleTap(event) > doubleTapSlop) {
          performWaitingDoubleTap()
          return false
        }
        handler.removeCallbacks(doubleTapTimeout)
        thirdTap = ThirdTap.DOWN
        thirdTapDownTime = event.eventTime
        return true
      }
      ThirdTap.DOWN -> {
        if (action == ACTION_UP) {
          thirdTap = ThirdTap.NONE
          val tripleTapAction =
            waitingEdge?.let {
              EdgeGestureSettings.action(prefs, it, EdgeGesture.TRIPLE_TAP, unassigned)
            }
          if (
            tripleTapAction != null &&
              event.eventTime - thirdTapDownTime < ViewConfiguration.getLongPressTimeout()
          ) {
            waitingEdge = null
            handler.post { service.performEdgeAction(tripleTapAction) }
          } else {
            performWaitingDoubleTap()
          }
        } else if (
          action == ACTION_POINTER_DOWN ||
            action == ACTION_CANCEL ||
            (action == ACTION_MOVE && distanceFromDoubleTap(event) > doubleTapSlop + tapSlop)
        ) {
          thirdTap = if (action == ACTION_CANCEL) ThirdTap.NONE else ThirdTap.SWALLOWING
          performWaitingDoubleTap()
        }
        return true
      }
      ThirdTap.SWALLOWING -> {
        if (action == ACTION_UP || action == ACTION_CANCEL) {
          thirdTap = ThirdTap.NONE
        }
        return true
      }
    }
  }
}
