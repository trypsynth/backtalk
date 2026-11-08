/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCommandPhrasesTest {

  private fun norm(spoken: String) = VoiceCommandFuzzyMatcher.normalize(spoken)

  @Test
  fun unmuteIsNotMute() {
    assertEquals(VolumeAction.UNMUTE, volumeAction(norm("unmute")))
    assertEquals(VolumeAction.UNMUTE, volumeAction(norm("unmute volume")))
    assertEquals(VolumeAction.UNMUTE, volumeAction(norm("please unmute")))
    assertEquals(VolumeAction.UNMUTE, volumeAction(norm("un mute")))
    assertEquals(VolumeAction.MUTE, volumeAction(norm("mute")))
    assertEquals(VolumeAction.MUTE, volumeAction(norm("mute audio")))
  }

  @Test
  fun volumeUpAndDown() {
    assertEquals(VolumeAction.UP, volumeAction(norm("volume up")))
    assertEquals(VolumeAction.UP, volumeAction(norm("louder")))
    assertEquals(VolumeAction.DOWN, volumeAction(norm("volume down")))
    assertEquals(VolumeAction.DOWN, volumeAction(norm("lower volume")))
  }

  @Test
  fun otherSentencesAreNotVolumeCommands() {
    assertNull(volumeAction(norm("open settings")))
    assertNull(volumeAction(norm("tell me about the volume of a sphere")))
    assertNull(volumeAction(norm("")))
  }

  @Test
  fun statusIsNotBattery() {
    assertEquals(DeviceInfo.STATUS, deviceInfo(norm("status")))
    assertEquals(DeviceInfo.STATUS, deviceInfo(norm("device status")))
    assertEquals(DeviceInfo.STATUS, deviceInfo(norm("what time is it")))
    assertEquals(DeviceInfo.BATTERY, deviceInfo(norm("battery")))
    assertEquals(DeviceInfo.BATTERY, deviceInfo(norm("battery status")))
    assertEquals(DeviceInfo.BATTERY, deviceInfo(norm("how much battery")))
  }

  @Test
  fun otherSentencesAreNotDeviceInfo() {
    assertNull(deviceInfo(norm("open settings")))
    assertNull(deviceInfo(norm("what is the status of my order")))
  }

  @Test
  fun openSettingsMeansAndroidSettings() {
    assertTrue(wantsSystemSettings(norm("open settings")))
    assertTrue(wantsSystemSettings(norm("settings")))
    assertTrue(wantsSystemSettings(norm("please open settings")))
    assertTrue(wantsSystemSettings(norm("go to settings")))
  }

  @Test
  fun backtalkSettingsIsNotAndroidSettings() {
    assertFalse(wantsSystemSettings(norm("open backtalk settings")))
    assertFalse(wantsSystemSettings(norm("open back talk settings")))
    assertFalse(wantsSystemSettings(norm("open talkback settings")))
    assertFalse(wantsSystemSettings(norm("open the wifi settings")))
  }

  @Test
  fun backTalkInTwoWordsIsBacktalk() {
    assertEquals("open backtalk settings", norm("open back talk settings"))
    assertEquals("backtalk settings", norm("back talk settings"))
  }
}
