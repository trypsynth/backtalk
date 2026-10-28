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

import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.time.Duration
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeFocusWarmUpTest {
  // Holds the background work until a test lets the app "answer".
  private val pending = ArrayDeque<Runnable>()
  private val held = Executor { pending.add(it) }
  private lateinit var original: Executor
  private val node = AccessibilityNodeInfoCompat.obtain()
  private var restores = 0

  @Before
  fun holdBackgroundWork() {
    original = WakeFocusWarmUp.executor
    WakeFocusWarmUp.executor = held
  }

  @After
  fun restoreExecutor() {
    WakeFocusWarmUp.executor = original
  }

  @Test
  fun restore_waitsForTheApp() {
    WakeFocusWarmUp.restoreAfterAppAnswers(node) { restores++ }
    idleMain()
    assertEquals(0, restores)

    answer()
    assertEquals(1, restores)
  }

  @Test
  fun restore_runsAfterTheTimeout_andOnlyOnce() {
    WakeFocusWarmUp.restoreAfterAppAnswers(node) { restores++ }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WakeFocusWarmUp.TIMEOUT_MS))
    assertEquals(1, restores)

    answer()
    assertEquals(1, restores)
  }

  @Test
  fun cancel_dropsTheRestore() {
    WakeFocusWarmUp.restoreAfterAppAnswers(node) { restores++ }
    WakeFocusWarmUp.cancel()
    answer()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WakeFocusWarmUp.TIMEOUT_MS))
    assertEquals(0, restores)
  }

  @Test
  fun aLaterRequest_dropsTheEarlierOne() {
    var earlier = 0
    WakeFocusWarmUp.restoreAfterAppAnswers(node) { earlier++ }
    WakeFocusWarmUp.restoreAfterAppAnswers(node) { restores++ }
    answer()
    answer()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WakeFocusWarmUp.TIMEOUT_MS))
    assertEquals(0, earlier)
    assertEquals(1, restores)
  }

  /** Lets the oldest background fetch finish, then runs what it posts to the main thread. */
  private fun answer() {
    pending.removeFirst().run()
    idleMain()
  }

  private fun idleMain() {
    shadowOf(Looper.getMainLooper()).idle()
  }
}
