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
import com.google.android.accessibility.talkback.compositor.OffScreenChildren.isOffScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OffScreenChildrenTest {
  // A Sound settings volume row, and the slider in it.
  private val item = Rect(0, 716, 1080, 983)
  private val slider = Rect(84, 815, 870, 941)

  @Test
  fun aHiddenChildInsideItsItemIsOnScreen() {
    assertFalse(isOffScreen(bounds = slider, parentBounds = item))
  }

  @Test
  fun aHiddenChildWithNoBoundsIsOffScreen() {
    assertTrue(isOffScreen(bounds = Rect(), parentBounds = item))
  }

  @Test
  fun aHiddenChildPastItsItemIsOffScreen() {
    // An item cut off at the bottom of the screen, with a line of text below the cut.
    val shownPart = Rect(0, 2200, 1080, 2340)
    val textBelow = Rect(40, 2340, 1040, 2420)
    assertTrue(isOffScreen(bounds = textBelow, parentBounds = shownPart))
  }
}
