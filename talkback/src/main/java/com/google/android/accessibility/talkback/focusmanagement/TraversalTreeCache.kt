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

package com.google.android.accessibility.talkback.focusmanagement

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy

/**
 * Keeps the reading order of the window that the user last swiped in, so that swiping through a
 * screen that has not changed does not ask the app for every node again. Any event that can change
 * that window throws the order away, the same way the framework's own node cache is kept current.
 *
 * Filter logcat by the "BacktalkTreeCache" tag in debug builds to see how often the order is reused.
 */
object TraversalTreeCache {
  private const val TAG = "BacktalkTreeCache"

  /** Events that never change the nodes or their order. */
  private const val IGNORED_EVENT_TYPES =
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
      AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
      AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
      AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
      AccessibilityEvent.TYPE_ANNOUNCEMENT or
      AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE or
      AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY or
      AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT or
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

  /** Node actions that only move focus, and so do not change the nodes or their order. */
  private val FOCUS_ACTIONS =
    setOf(
      AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_FOCUS,
      AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS,
    )

  private const val NO_WINDOW_ID = -1

  private var root: AccessibilityNodeInfoCompat? = null
  private var strategy: OrderedTraversalStrategy? = null
  private var lastClearReason = "start"
  private var hits = 0
  private var misses = 0

  /** Returns the saved order of [root] if it has one and it contains [pivot]. */
  @JvmStatic
  fun get(
    root: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
  ): OrderedTraversalStrategy? {
    val saved = strategy
    val result = saved?.takeIf { root == this.root && it.containsNode(pivot) }
    if (BuildConfig.DEBUG) {
      if (result != null) {
        hits++
        Log.d(TAG, "Reused order ($hits reused, $misses built)")
      } else {
        misses++
        val reason =
          when {
            saved == null -> "cleared by $lastClearReason"
            root != this.root -> "different window"
            else -> "focused node not in order"
          }
        Log.d(TAG, "Building order, $reason ($hits reused, $misses built)")
      }
    }
    return result
  }

  /** Saves the order of [root], replacing any saved order. */
  @JvmStatic
  fun put(root: AccessibilityNodeInfoCompat, strategy: OrderedTraversalStrategy) {
    this.root = root
    this.strategy = strategy
  }

  /** Throws away the saved order. */
  @JvmStatic
  fun clear(reason: String) {
    if (strategy == null) {
      return
    }
    root = null
    strategy = null
    lastClearReason = reason
  }

  /**
   * Throws away the saved order before TalkBack performs [actionId] on a node, unless the action
   * only moves focus. A scroll or click changes the screen before the app's change event reaches
   * us, and TalkBack can navigate again in between, such as right after it scrolls a list.
   */
  @JvmStatic
  fun onNodeAction(actionId: Int) {
    if (actionId !in FOCUS_ACTIONS) {
      clear(if (BuildConfig.DEBUG) AccessibilityNodeInfoUtils.actionToString(actionId) else "")
    }
  }

  /** Throws away the saved order if [event] can change its window. */
  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent) {
    val savedRoot = root ?: return
    val type = event.eventType
    if (type and IGNORED_EVENT_TYPES != 0) {
      return
    }
    val windowId = event.windowId
    if (
      type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
        type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        windowId == NO_WINDOW_ID ||
        windowId == savedRoot.windowId
    ) {
      clear(if (BuildConfig.DEBUG) AccessibilityEvent.eventTypeToString(type) else "")
    }
  }
}
