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

package com.google.android.accessibility.talkback.selector

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The reading control for a touched item is picked a moment after the touch, not during it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LateSettingSwitchTest {
  private val picked = mutableListOf<AccessibilityNodeInfo>()
  private val late = LateSettingSwitch(Handler(Looper.getMainLooper())) { picked.add(it) }
  private val key = AccessibilityNodeInfo()
  private val nextKey = AccessibilityNodeInfo()

  private fun assertPicked(node: AccessibilityNodeInfo) {
    assertEquals(1, picked.size)
    assertSame(node, picked[0])
  }

  private fun waitFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  @Test
  fun picksAMomentAfterTheTouchEnds() {
    late.hold(key)
    late.schedule()
    assertEquals(0, picked.size)
    waitFor(LateSettingSwitch.DELAY_MS)
    assertPicked(key)
  }

  @Test
  fun aReadingControlPicksAtOnce() {
    late.hold(key)
    late.schedule()
    late.runNow()
    assertPicked(key)
    waitFor(LateSettingSwitch.DELAY_MS)
    assertPicked(key)
  }

  @Test
  fun aNewerFocusWins() {
    late.hold(key)
    late.schedule()
    late.cancel()
    waitFor(LateSettingSwitch.DELAY_MS)
    late.runNow()
    assertEquals(0, picked.size)
  }

  @Test
  fun aNewTouchWaitsAndKeepsTheItem() {
    late.hold(key)
    late.schedule()
    late.pause()
    waitFor(LateSettingSwitch.DELAY_MS)
    assertEquals(0, picked.size)
    late.schedule()
    waitFor(LateSettingSwitch.DELAY_MS)
    assertPicked(key)
  }

  @Test
  fun theNextTouchedItemReplacesTheWaitingOne() {
    late.hold(key)
    late.schedule()
    late.pause()
    late.hold(nextKey)
    late.schedule()
    waitFor(LateSettingSwitch.DELAY_MS)
    assertPicked(nextKey)
  }
}
