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

import android.content.SharedPreferences

enum class Edge(internal val prefName: String) {
  LEFT("left"),
  RIGHT("right"),
}

enum class EdgeGesture(internal val prefName: String) {
  SWIPE_UP("swipe_up"),
  SWIPE_DOWN("swipe_down"),
  TRIPLE_TAP("triple_tap"),
}

/** A gesture without an action on an edge does what it does anywhere else. */
object EdgeGestureSettings {
  const val PREF_ENABLED = "pref_edge_gestures"

  const val PREF_WIDTH = "pref_edge_width"
  const val MIN_WIDTH_QUARTERS = 2
  const val MAX_WIDTH_QUARTERS = 8
  const val DEFAULT_WIDTH_QUARTERS = 4

  @JvmStatic fun widthCm(prefs: SharedPreferences): Float = widthQuarters(prefs) / 4f

  @JvmStatic
  fun widthQuarters(prefs: SharedPreferences): Int =
    prefs
      .getInt(PREF_WIDTH, DEFAULT_WIDTH_QUARTERS)
      .coerceIn(MIN_WIDTH_QUARTERS, MAX_WIDTH_QUARTERS)

  @JvmStatic
  fun gestureKey(edge: Edge, gesture: EdgeGesture): String =
    "pref_edge_${edge.prefName}_${gesture.prefName}"

  @JvmStatic
  fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, false)

  @JvmStatic
  fun action(
    prefs: SharedPreferences,
    edge: Edge,
    gesture: EdgeGesture,
    unassigned: String,
  ): String? =
    prefs.getString(gestureKey(edge, gesture), null)?.takeIf {
      it.isNotEmpty() && it != unassigned
    }

  @JvmStatic
  fun edgeAt(x: Float, screenWidth: Float, edgeWidth: Float): Edge? =
    when {
      x < edgeWidth -> Edge.LEFT
      x >= screenWidth - edgeWidth -> Edge.RIGHT
      else -> null
    }
}
