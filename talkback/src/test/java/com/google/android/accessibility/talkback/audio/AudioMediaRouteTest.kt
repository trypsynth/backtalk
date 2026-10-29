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

package com.google.android.accessibility.talkback.audio

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.Companion.Endpoint
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.Companion.playsAsMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a chosen output plays Backtalk's audio as media, without the call route (#316). */
class AudioMediaRouteTest {
  private val callProfile = Endpoint(TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:FF", "Buds")
  private val mediaProfile = Endpoint(TYPE_BLUETOOTH_A2DP, "aa:bb:cc:dd:ee:ff", "Buds")

  @Test
  fun aHeadsetsCallProfile_playsAsMedia_whenItsMediaProfilePlays() {
    assertTrue(playsAsMedia(callProfile, listOf(mediaProfile)))
  }

  @Test
  fun aHeadset_doesNotPlayAsMedia_whenMediaPlaysElsewhere() {
    val otherHeadset = Endpoint(TYPE_BLUETOOTH_A2DP, "11:22:33:44:55:66", "Speaker")
    val speaker = Endpoint(TYPE_BUILTIN_SPEAKER, "", "Phone")
    assertFalse(playsAsMedia(callProfile, listOf(otherHeadset, speaker)))
  }

  @Test
  fun theCallProfileItself_isNotMedia() {
    assertFalse(playsAsMedia(callProfile, listOf(callProfile)))
  }

  @Test
  fun leAudioHeadphones_playAsMedia() {
    val le = Endpoint(TYPE_BLE_HEADSET, "12:34:56:78:9A:BC", "Pixel Buds")
    assertTrue(playsAsMedia(le, listOf(le)))
  }

  @Test
  fun withoutAddresses_theNameDecides() {
    assertTrue(
      playsAsMedia(
        Endpoint(TYPE_BLUETOOTH_SCO, "", "Buds"),
        listOf(Endpoint(TYPE_BLUETOOTH_A2DP, "", "buds")),
      )
    )
  }

  @Test
  fun wiredHeadphones_keepTheirRoute() {
    val wired = Endpoint(TYPE_WIRED_HEADPHONES, "", "Headphones")
    assertFalse(playsAsMedia(wired, listOf(wired)))
  }
}
