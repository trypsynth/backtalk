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

package com.google.android.accessibility.talkback.monitor

import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.STREAM_ACCESSIBILITY
import android.media.AudioManager.STREAM_MUSIC
import android.os.Looper
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
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
class QuietBeforeAnnouncementTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  private lateinit var audioManager: AudioManager
  private var onBluetooth = true
  private var spoken = 0
  private lateinit var quiet: QuietBeforeAnnouncement

  @Before
  fun setUp() {
    audioManager = context.getSystemService(AudioManager::class.java)
    shadowOf(audioManager).setStreamMaxVolume(15)
    audioManager.setStreamVolume(STREAM_ACCESSIBILITY, 5, 0)
    audioManager.setStreamVolume(STREAM_MUSIC, 8, 0)
    SharedPreferencesUtils.putBooleanPref(
      SharedPreferencesUtils.getSharedPreferences(context),
      context.resources,
      R.string.pref_steady_bluetooth_volume_key,
      true,
    )
    quiet = QuietBeforeAnnouncement(context) { onBluetooth }
    quiet.start()
  }

  private fun announceMediaVolume() {
    quiet.announce(STREAM_MUSIC, false, {}, { spoken++ })
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
  }

  @Test
  fun nudgesOneStepDownAndBack() {
    announceMediaVolume()

    assertEquals(1, spoken)
    assertEquals(5, audioManager.getStreamVolume(STREAM_ACCESSIBILITY))
    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
  }

  @Test
  fun nothingIsNudgedWhenTheSettingIsOff() {
    SharedPreferencesUtils.putBooleanPref(
      SharedPreferencesUtils.getSharedPreferences(context),
      context.resources,
      R.string.pref_steady_bluetooth_volume_key,
      false,
    )

    announceMediaVolume()

    assertEquals(1, spoken)
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun recognizesANudgeWhenOnlyItsLastChangeIsReported() {
    announceMediaVolume()

    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun aChangeByAnyoneElseIsSaidAndEndsTheNudge() {
    announceMediaVolume()

    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 9))
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun nudgesOnceForReportsOfTheSameMediaChange() {
    quiet.announce(STREAM_MUSIC, false, {}, {})
    announceMediaVolume()

    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
  }

  @Test
  fun nudgesAgainForTheSameMediaVolumeLater() {
    announceMediaVolume()
    announceMediaVolume()

    assertEquals(2, spoken)
    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
    assertTrue(quiet.isOwnChange(STREAM_ACCESSIBILITY, 5))
  }

  @Test
  fun forgetsTheNudgeWhenStopped() {
    announceMediaVolume()
    quiet.stop()

    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun leavesTheVolumeAloneWithoutBluetooth() {
    onBluetooth = false

    announceMediaVolume()

    assertEquals(1, spoken)
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun leavesOtherVolumesAlone() {
    quiet.announce(AudioManager.STREAM_RING, false, {}, { spoken++ })

    assertEquals(1, spoken)
    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }

  @Test
  fun forgetsANudgeAndroidNeverReported() {
    announceMediaVolume()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    assertFalse(quiet.isOwnChange(STREAM_ACCESSIBILITY, 4))
  }
}
