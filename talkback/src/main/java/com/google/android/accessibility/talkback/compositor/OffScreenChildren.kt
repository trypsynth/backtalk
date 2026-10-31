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

package com.google.android.accessibility.talkback.compositor

import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Finds the children of an item that are off screen, which a swipe may scroll into view. */
object OffScreenChildren {

  /** Whether a child of [node] is off screen. */
  @JvmStatic
  fun hasAny(node: AccessibilityNodeInfoCompat): Boolean {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val childBounds = Rect()
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      if (child.isVisibleToUser) {
        continue
      }
      child.getBoundsInScreen(childBounds)
      if (isOffScreen(childBounds, bounds)) {
        return true
      }
    }
    return false
  }

  /**
   * Whether a child that the app reports as not visible is off screen. One that lies inside its
   * parent is not: some apps report a slider in a list item that way, such as the volume rows in
   * Sound settings, and no scroll would change it.
   */
  internal fun isOffScreen(bounds: Rect, parentBounds: Rect): Boolean =
    bounds.isEmpty || !parentBounds.contains(bounds)
}
