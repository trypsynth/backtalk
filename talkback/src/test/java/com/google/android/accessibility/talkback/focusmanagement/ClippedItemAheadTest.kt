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

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForLogicalNavigation.findClippedItemAhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Before a page scroll, the part of the list cut off ahead of the pivot is brought on screen, since
 * it has nothing to focus yet and the page scroll would carry it past the other edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClippedItemAheadTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun node(top: Int, bottom: Int): AccessibilityNodeInfo =
    AccessibilityNodeInfo.obtain(View(context)).apply {
      setVisibleToUser(true)
      setBoundsInScreen(Rect(0, top, 100, bottom))
    }

  private val list = node(0, 1000)

  /** Adds an item to the list with a pivot inside it. Returns the item and its pivot. */
  private fun item(top: Int, bottom: Int): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
    val item = node(top, bottom)
    val pivot = node(top, minOf(top + 10, bottom))
    shadowOf(list).addChild(item)
    shadowOf(item).addChild(pivot)
    return item to pivot
  }

  private fun find(pivot: AccessibilityNodeInfo, forward: Boolean) =
    findClippedItemAhead(
      AccessibilityNodeInfoCompat.wrap(list),
      AccessibilityNodeInfoCompat.wrap(pivot),
      forward,
    )

  @Test
  fun forward_returnsTheItemHoldingThePivotWhenItIsCutOff() {
    val (holder, pivot) = item(600, 1000)

    assertEquals(AccessibilityNodeInfoCompat.wrap(holder), find(pivot, forward = true))
  }

  @Test
  fun forward_returnsTheNextItemWhenOnlyItIsCutOff() {
    val (_, pivot) = item(0, 700)
    val (next, _) = item(700, 1000)

    assertEquals(AccessibilityNodeInfoCompat.wrap(next), find(pivot, forward = true))
  }

  @Test
  fun forward_returnsNothingWhenAllIsOnScreen() {
    val (_, pivot) = item(0, 400)
    item(400, 800)

    assertNull(find(pivot, forward = true))
  }

  @Test
  fun backward_returnsThePreviousItemWhenOnlyItIsCutOff() {
    val (previous, _) = item(0, 300)
    val (_, pivot) = item(300, 700)

    assertEquals(AccessibilityNodeInfoCompat.wrap(previous), find(pivot, forward = false))
  }

  /** A card in a row of cards reaches both edges of the row, and so does one taller than the list. */
  @Test
  fun returnsNothingForAnItemReachingBothEdges() {
    val (_, pivot) = item(0, 1000)
    item(0, 1000)

    assertNull(find(pivot, forward = true))
    assertNull(find(pivot, forward = false))
  }
}
