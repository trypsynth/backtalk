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

package com.google.android.accessibility.talkback

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.STREAM_ACCESSIBILITY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityVolumeStepTest {
  private lateinit var audioManager: AudioManager

  private var volume: Int
    get() = audioManager.getStreamVolume(STREAM_ACCESSIBILITY)
    set(value) = audioManager.setStreamVolume(STREAM_ACCESSIBILITY, value, 0)

  private fun raise() = AccessibilityVolumeStep.step(audioManager, decrease = false, flags = 0)

  private fun lower() = AccessibilityVolumeStep.step(audioManager, decrease = true, flags = 0)

  @Before
  fun setUp() {
    audioManager =
      RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    shadowOf(audioManager).setStreamMaxVolume(MAX)
  }

  @Test
  fun raisesAndLowersByOneStep() {
    volume = 5

    assertTrue(raise())
    assertEquals(6, volume)
    assertTrue(lower())
    assertEquals(5, volume)
  }

  @Test
  fun stopsAtTheHighestStep() {
    volume = MAX

    assertFalse(raise())
    assertEquals(MAX, volume)
    assertTrue(lower())
    assertEquals(MAX - 1, volume)
  }

  @Test
  fun neverGoesSilent() {
    volume = 1

    assertFalse(lower())
    assertEquals(1, volume)
    assertTrue(raise())
    assertEquals(2, volume)
  }

  @Test
  fun stopsAtTheStreamMinimum() {
    shadowOf(audioManager).setStreamMinVolume(3)
    volume = 3

    assertFalse(lower())
    assertEquals(3, volume)
  }

  @Test
  fun raisesASilentStream() {
    volume = 0

    assertTrue(raise())
    assertEquals(1, volume)
  }

  @Test
  fun leavesASingleStepStreamAlone() {
    shadowOf(audioManager).setStreamMaxVolume(1)
    volume = 1

    assertFalse(raise())
    assertFalse(lower())
    assertEquals(1, volume)
  }

  private companion object {
    const val MAX = 15
  }
}
