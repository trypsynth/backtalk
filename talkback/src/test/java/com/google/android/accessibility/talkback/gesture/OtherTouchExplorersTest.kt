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

import android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
import com.google.android.accessibility.talkback.gesture.OtherTouchExplorers.Companion.isOtherTouchExplorer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtherTouchExplorersTest {
  private val backtalk = "dev.backtalk"

  @Test
  fun otherServiceExploringByTouch_counts() {
    assertTrue(
      isOtherTouchExplorer(
        "com.example.helper/.HelperService",
        FLAG_REQUEST_TOUCH_EXPLORATION_MODE or FLAG_REPORT_VIEW_IDS,
        backtalk,
      )
    )
  }

  @Test
  fun otherServiceNotExploringByTouch_doesNotCount() {
    assertFalse(
      isOtherTouchExplorer("com.example.helper/.HelperService", FLAG_REPORT_VIEW_IDS, backtalk)
    )
  }

  @Test
  fun ownService_doesNotCount() {
    assertFalse(
      isOtherTouchExplorer(
        "$backtalk/com.google.android.marvin.talkback.TalkBackService",
        FLAG_REQUEST_TOUCH_EXPLORATION_MODE,
        backtalk,
      )
    )
  }

  @Test
  fun serviceWithoutId_counts() {
    assertTrue(isOtherTouchExplorer(null, FLAG_REQUEST_TOUCH_EXPLORATION_MODE, backtalk))
  }
}
