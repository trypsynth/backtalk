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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityVolumeStepTest {
  private lateinit var audioManager: AudioManager

  @Before
  fun setUp() {
    audioManager =
      RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }

  @Test
  fun raisesAndLowersByOneStep() {
    audioManager.setStreamVolume(STREAM_ACCESSIBILITY, 5, 0)

    assertTrue(AccessibilityVolumeStep.step(audioManager, decrease = false, flags = 0))
    assertEquals(6, audioManager.getStreamVolume(STREAM_ACCESSIBILITY))
    assertTrue(AccessibilityVolumeStep.step(audioManager, decrease = true, flags = 0))
    assertEquals(5, audioManager.getStreamVolume(STREAM_ACCESSIBILITY))
  }

  @Test
  fun stopsAtTheHighestStep() {
    val max = audioManager.getStreamMaxVolume(STREAM_ACCESSIBILITY)
    audioManager.setStreamVolume(STREAM_ACCESSIBILITY, max, 0)

    assertFalse(AccessibilityVolumeStep.step(audioManager, decrease = false, flags = 0))
    assertEquals(max, audioManager.getStreamVolume(STREAM_ACCESSIBILITY))
  }

  @Test
  fun neverGoesSilent() {
    audioManager.setStreamVolume(STREAM_ACCESSIBILITY, 1, 0)

    assertFalse(AccessibilityVolumeStep.step(audioManager, decrease = true, flags = 0))
    assertEquals(1, audioManager.getStreamVolume(STREAM_ACCESSIBILITY))
  }
}
