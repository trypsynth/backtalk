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

package com.google.android.accessibility.talkback.actor.gemini.local

import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings.Support
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceAiSettingsTest {
  private val gib = 1024L * 1024L * 1024L
  private val arm64 = listOf("arm64-v8a", "armeabi-v7a")

  @Test
  fun cloudIsTheDefaultProvider() {
    assertFalse(OnDeviceAiSettings.isOnDevice(FakeSharedPreferences()))
  }

  @Test
  fun providerModelAndGpuAreRemembered() {
    val prefs = FakeSharedPreferences()
    OnDeviceAiSettings.setOnDevice(prefs, true)
    OnDeviceAiSettings.setPreferredModel(prefs, LocalModel.E4B)
    OnDeviceAiSettings.setUseGpu(prefs, true)
    assertTrue(OnDeviceAiSettings.isOnDevice(prefs))
    assertEquals(LocalModel.E4B, OnDeviceAiSettings.preferredModel(prefs))
    assertTrue(OnDeviceAiSettings.useGpu(prefs))
    OnDeviceAiSettings.setOnDevice(prefs, false)
    assertFalse(OnDeviceAiSettings.isOnDevice(prefs))
  }

  @Test
  fun smallerModelIsTheDefault() {
    assertEquals(LocalModel.E2B, OnDeviceAiSettings.preferredModel(FakeSharedPreferences()))
  }

  @Test
  fun modelsThatNeedMoreMemoryAreHidden() {
    val models = OnDeviceAiSettings.modelsFor(8 * gib) { false }
    assertTrue(LocalModel.E4B in models)
    assertFalse(models.any { it.id == "gemma4-12b" })
    assertTrue(models.all { OnDeviceAiSettings.fits(it, 8 * gib) })
  }

  @Test
  fun installedModelsStayListedEvenWhenTooBig() {
    val models = OnDeviceAiSettings.modelsFor(4 * gib) { it == LocalModel.E4B }
    assertTrue(LocalModel.E4B in models)
    assertFalse(LocalModel.E2B in models)
  }

  @Test
  fun defaultIsE2bFromTwelveGig() {
    // A 12 GB phone reports about 10.9 GB.
    assertEquals(LocalModel.E2B, OnDeviceAiSettings.defaultModel((10.9 * gib).toLong()))
    assertEquals(
      LocalModel.E2B,
      OnDeviceAiSettings.preferredModel(FakeSharedPreferences(), 16 * gib),
    )
  }

  @Test
  fun defaultOnAnEightGigPhoneIsAFifthOfItsRamAtMost() {
    // An 8 GB phone, such as a Pixel 9a, reports about 7.4 GB.
    val ram = (7.4 * gib).toLong()
    val model = OnDeviceAiSettings.defaultModel(ram)
    assertNotEquals(LocalModel.E2B, model)
    assertTrue(OnDeviceAiSettings.fits(model, ram))
    assertTrue(model.sizeBytes <= ram / 5)
    val largest =
      LocalModel.entries
        .filter { OnDeviceAiSettings.fits(it, ram) && it.sizeBytes <= ram / 5 }
        .maxOf { it.sizeBytes }
    assertEquals(largest, model.sizeBytes)
  }

  @Test
  fun defaultOnSmallPhonesStillFits() {
    val model = OnDeviceAiSettings.defaultModel(4 * gib)
    assertTrue(OnDeviceAiSettings.fits(model, 4 * gib))
    assertTrue(model.sizeBytes <= 4 * gib / 5)
  }

  @Test
  fun theLargestModelNeedsASixteenGigPhone() {
    val gemma12b = LocalModel.entries.single { it.id == "gemma4-12b" }
    // A 12 GB phone reports about 10.9 GB, and was stopped by Android loading this model.
    assertEquals(
      Support.NOT_ENOUGH_RAM,
      OnDeviceAiSettings.support(gemma12b, (10.9 * gib).toLong(), arm64),
    )
    assertEquals(Support.OK, OnDeviceAiSettings.support(gemma12b, (14.8 * gib).toLong(), arm64))
  }

  @Test
  fun aModelLoadsOnlyWhenTheFreeMemoryHoldsIt() {
    val model = 3 * gib
    assertTrue(OnDeviceAiSettings.hasFreeMemoryFor(model, 4 * gib, lowMemory = false))
    assertTrue(OnDeviceAiSettings.hasFreeMemoryFor(model, model, lowMemory = false))
    assertFalse(OnDeviceAiSettings.hasFreeMemoryFor(model, 2 * gib, lowMemory = false))
  }

  @Test
  fun aModelDoesNotLoadWhenAndroidIsLowOnMemory() {
    assertFalse(OnDeviceAiSettings.hasFreeMemoryFor(gib, 8 * gib, lowMemory = true))
  }

  @Test
  fun a32BitPhoneIsUnsupported() {
    assertEquals(
      Support.UNSUPPORTED_CPU,
      OnDeviceAiSettings.support(LocalModel.E2B, 12 * gib, listOf("armeabi-v7a")),
    )
  }

  @Test
  fun anEightGigPhoneThatReportsLessRamStillRunsBothModels() {
    // An 8 GB phone reports about 7.3 GB to Android.
    val reported = (7.3 * gib).toLong()
    assertEquals(Support.OK, OnDeviceAiSettings.support(LocalModel.E2B, reported, arm64))
    assertEquals(Support.OK, OnDeviceAiSettings.support(LocalModel.E4B, reported, arm64))
  }

  @Test
  fun aFourGigPhoneIsTooSmall() {
    assertEquals(
      Support.NOT_ENOUGH_RAM,
      OnDeviceAiSettings.support(LocalModel.E2B, (3.7 * gib).toLong(), arm64),
    )
  }

  @Test
  fun aSixGigPhoneRunsTheSmallModelButNotTheLargeOne() {
    val reported = (5.5 * gib).toLong()
    assertEquals(Support.OK, OnDeviceAiSettings.support(LocalModel.E2B, reported, arm64))
    assertEquals(Support.NOT_ENOUGH_RAM, OnDeviceAiSettings.support(LocalModel.E4B, reported, arm64))
  }

  @Test
  fun timeoutDefaultsToThreeMinutesAndKeepsAValidChoice() {
    val prefs = FakeSharedPreferences()
    assertEquals(180, OnDeviceAiSettings.timeoutSeconds(prefs))
    OnDeviceAiSettings.setTimeoutSeconds(prefs, 300)
    assertEquals(300, OnDeviceAiSettings.timeoutSeconds(prefs))
  }

  @Test
  fun anUnknownTimeoutFallsBackToTheDefault() {
    val prefs = FakeSharedPreferences()
    OnDeviceAiSettings.setTimeoutSeconds(prefs, 7)
    assertEquals(180, OnDeviceAiSettings.timeoutSeconds(prefs))
  }

  @Test
  fun shortPromptsAreOffUntilTheUserTurnsThemOn() {
    val prefs = FakeSharedPreferences()
    assertFalse(OnDeviceAiSettings.shortPrompts(prefs))
    OnDeviceAiSettings.setShortPrompts(prefs, true)
    assertTrue(OnDeviceAiSettings.shortPrompts(prefs))
  }
}
