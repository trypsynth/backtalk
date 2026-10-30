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

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils

/** What is on the screen at a point, for a finger that edge gestures keep from exploring. */
object ScreenPoint {

  /** Returns the window on top at ([x], [y]), or null if none of [windows] is there. */
  @JvmStatic
  fun windowAt(windows: List<AccessibilityWindowInfo>, x: Int, y: Int): AccessibilityWindowInfo? =
    windows
      .filter { AccessibilityWindowInfoUtils.getRegionInScreen(it)?.contains(x, y) == true }
      .maxByOrNull { it.layer }

  /** Whether the keyboard is on top at ([x], [y]). */
  @JvmStatic
  fun isOnKeyboard(windows: List<AccessibilityWindowInfo>, x: Int, y: Int): Boolean =
    AccessibilityWindowInfoUtils.isImeWindow(windowAt(windows, x, y))

  /**
   * Returns the node that touch exploration would focus at ([x], [y]): the innermost visible node
   * drawn there, or its nearest ancestor that can take focus.
   */
  @JvmStatic
  fun focusableNodeAt(
    windows: List<AccessibilityWindowInfo>,
    x: Int,
    y: Int,
  ): AccessibilityNodeInfoCompat? {
    val window = windowAt(windows, x, y) ?: return null
    var node = AccessibilityWindowInfoUtils.getRootCompat(window) ?: return null
    while (true) {
      node = childAt(node, x, y) ?: break
    }
    return AccessibilityNodeInfoUtils.findFocusFromHover(node)
  }

  /** Returns the visible child of [node] drawn on top at ([x], [y]), or null if none is there. */
  private fun childAt(
    node: AccessibilityNodeInfoCompat,
    x: Int,
    y: Int,
  ): AccessibilityNodeInfoCompat? =
    (node.childCount - 1 downTo 0)
      .mapNotNull { node.getChild(it) }
      .sortedByDescending { it.drawingOrder }
      .firstOrNull { it.isVisibleToUser && Rect().also(it::getBoundsInScreen).contains(x, y) }
}
