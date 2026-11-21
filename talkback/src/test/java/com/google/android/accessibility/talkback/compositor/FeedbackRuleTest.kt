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

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.compositor.Compositor.EVENT_TYPE_VIEW_CLICKED
import com.google.android.accessibility.talkback.compositor.Compositor.EVENT_UNKNOWN
import java.util.function.Function
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Events without a feedback rule, such as hover exits, are known to give nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackRuleTest {
  private val provider =
    TalkBackFeedbackProvider(
      mapOf(EVENT_TYPE_VIEW_CLICKED to Function { _ -> EventFeedback.builder().build() })
    )

  @Test
  fun anEventWithARuleCanGiveFeedback() {
    assertTrue(provider.hasFeedbackRule(EVENT_TYPE_VIEW_CLICKED))
  }

  @Test
  fun aHoverExitHasNoRule() {
    val hoverExit = AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    assertFalse(provider.hasFeedbackRule(Compositor.toCompositorEvent(hoverExit)))
    assertFalse(provider.hasFeedbackRule(EVENT_UNKNOWN))
  }
}
