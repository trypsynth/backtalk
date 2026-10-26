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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyClickCuesTest {
  private var now = 0L
  private val cues = EarlyClickCues(clock = { now })

  @Test
  fun aClickNotGivenEarlyGetsItsCuesLater() {
    assertFalse(cues.take(100L))
  }

  @Test
  fun aClickGivenEarlyIsSkippedOnce() {
    cues.given(100L)
    assertTrue(cues.take(100L))
    assertFalse(cues.take(100L))
  }

  @Test
  fun twoQuickClicksAreEachSkippedOnce() {
    cues.given(100L)
    now = 60L
    cues.given(160L)
    assertTrue(cues.take(100L))
    assertTrue(cues.take(160L))
    assertFalse(cues.take(160L))
  }

  @Test
  fun anotherClickIsNotSkipped() {
    cues.given(100L)
    assertFalse(cues.take(200L))
  }

  @Test
  fun aClickWhoseEventNeverCameIsForgotten() {
    cues.given(100L)
    now = 20_000L
    assertFalse(cues.take(100L))
  }
}
