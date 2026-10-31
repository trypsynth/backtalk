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

import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_DOWN
import com.google.android.accessibility.utils.traversal.TraversalStrategy.SEARCH_FOCUS_FORWARD
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only a plain swipe to the next or previous item scrolls the list ahead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrollAheadTest {
  private fun swipe() =
    NavigationAction.Builder()
      .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setDirection(SEARCH_FOCUS_FORWARD)
      .setTarget(NavigationTarget.TARGET_DEFAULT)
      .setShouldScroll(true)

  @Test
  fun plainSwipe_scrollsAhead() {
    assertTrue(FocusProcessorForLogicalNavigation.canScrollAheadAfter(swipe().build()))
  }

  @Test
  fun moveThatMustNotScroll_doesNotScrollAhead() {
    assertFalse(
      FocusProcessorForLogicalNavigation.canScrollAheadAfter(swipe().setShouldScroll(false).build())
    )
  }

  @Test
  fun spatialMove_doesNotScrollAhead() {
    assertFalse(
      FocusProcessorForLogicalNavigation.canScrollAheadAfter(
        swipe().setDirection(SEARCH_FOCUS_DOWN).build()
      )
    )
  }

  @Test
  fun headingMove_doesNotScrollAhead() {
    assertFalse(
      FocusProcessorForLogicalNavigation.canScrollAheadAfter(
        swipe().setTarget(NavigationTarget.TARGET_HEADING).build()
      )
    )
  }

  @Test
  fun scrollGesture_doesNotScrollAhead() {
    assertFalse(
      FocusProcessorForLogicalNavigation.canScrollAheadAfter(
        swipe().setAction(NavigationAction.SCROLL_FORWARD).build()
      )
    )
  }
}
