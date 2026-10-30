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
import com.google.android.accessibility.utils.traversal.OrderedTraversalController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * After a scroll, the next item is searched for from the node focused before it, without first
 * reading that node again from the app. So what it holds from before the scroll must not matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchAfterScrollTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun node(): AccessibilityNodeInfo =
    AccessibilityNodeInfo.obtain(View(context)).apply {
      setVisibleToUser(true)
      setBoundsInScreen(Rect(0, 0, 100, 100))
    }

  @Test
  fun findsTheNextNodeFromAPivotWithBoundsFromBeforeTheScroll() {
    val root = node()
    val first = node()
    val second = node()
    shadowOf(root).addChild(first)
    shadowOf(root).addChild(second)
    val controller = OrderedTraversalController()
    controller.initOrder(
      AccessibilityNodeInfoCompat.wrap(root),
      /* includeChildrenOfNodesWithWebActions= */ false,
    )

    @Suppress("DEPRECATION") // obtain(): a copy with its own bounds, as the pivot holds.
    val pivotFromBeforeScroll = AccessibilityNodeInfo.obtain(first)
    pivotFromBeforeScroll.setBoundsInScreen(Rect(0, 2000, 100, 2100))

    val next = controller.findNext(AccessibilityNodeInfoCompat.wrap(pivotFromBeforeScroll))

    assertNotNull(next)
    assertEquals(AccessibilityNodeInfoCompat.wrap(second), next)
  }
}
