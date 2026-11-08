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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAnnouncementVolumeTest {
  @Test
  fun twentyDecibelsQuieterIsATenthOfTheVolume() {
    assertEquals(0.1f, FinalAnnouncementVolume.gainFromDecibels(-40f, -20f), 1e-4f)
  }

  @Test
  fun neverLouderThanTheMediaVolume() {
    assertEquals(1f, FinalAnnouncementVolume.gainFromDecibels(-10f, -20f), 0f)
    assertEquals(1f, FinalAnnouncementVolume.gainFromDecibels(-20f, -20f), 0f)
  }

  @Test
  fun quietestAccessibilityStepsAreMuchQuieterThanTheSliderGuess() {
    // A typical phone: the lowest accessibility step is about -50 dB and half media volume about
    // -20 dB, 30 dB apart, while the slider guess only lowers the speech about 22 dB.
    val measured = FinalAnnouncementVolume.gainFromDecibels(-50f, -20f)
    val guessed = FinalAnnouncementVolume.sliderGain(1f / 15f, 0.5f)
    assertEquals(0.0316f, measured, 1e-4f)
    assertTrue(measured < guessed / 2f)
  }

  @Test
  fun decibelsAreUsedAsTheyAre() {
    assertEquals(0.1f, FinalAnnouncementVolume.gainFromReportedVolumes(-40f, -20f), 1e-4f)
    assertEquals(0.1f, FinalAnnouncementVolume.gainFromReportedVolumes(-20f, 0f), 1e-4f)
    // A volume curve that boosts media above 0 dB is still in decibels.
    assertEquals(0.1f, FinalAnnouncementVolume.gainFromReportedVolumes(-18f, 2f), 1e-4f)
    assertEquals(0.7943f, FinalAnnouncementVolume.gainFromReportedVolumes(0f, 2f), 1e-4f)
  }

  @Test
  fun samsungsFractionsOfFullLoudnessAreDivided() {
    // A Samsung phone on its speaker: accessibility volume 4 and media volume 7 out of 15.
    assertEquals(0.1288f, FinalAnnouncementVolume.gainFromReportedVolumes(0.01f, 0.0776f), 1e-4f)
    assertEquals(1f, FinalAnnouncementVolume.gainFromReportedVolumes(1f, 0.0776f), 0f)
    assertEquals(1f, FinalAnnouncementVolume.gainFromReportedVolumes(1f, 1f), 0f)
  }

  @Test
  fun mediaStepsAreMatchedToAccessibilitySteps() {
    // Media from step 0 and accessibility from step 1, both to 15, as on a Samsung phone, where
    // media 7 set accessibility 8 and media 8 set it to 9.
    assertEquals(8, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(7, 0, 15, 1, 15))
    assertEquals(9, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(8, 0, 15, 1, 15))
    // The emulator: media 9 set accessibility 9.
    assertEquals(9, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(9, 0, 15, 1, 15))
    assertEquals(15, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(15, 0, 15, 1, 15))
    assertEquals(9, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(9, 0, 15, 0, 15))
    assertEquals(3, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(5, 0, 25, 0, 15))
    assertEquals(15, FinalAnnouncementVolume.accessibilityStepAtMediaVolume(25, 0, 25, 0, 15))
  }

  @Test
  fun sliderGuessKeepsTalkBackBehavior() {
    assertEquals(1f, FinalAnnouncementVolume.sliderGain(0.6f, 0.4f), 0f)
    assertEquals(0.1f, FinalAnnouncementVolume.sliderGain(0.2f, 0.6f), 1e-4f)
  }
}
