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
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.STREAM_ACCESSIBILITY
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioManager.STREAM_NOTIFICATION
import android.media.AudioManager.STREAM_VOICE_CALL
import android.os.Looper
import android.view.KeyEvent
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.compat.media.AudioManagerCompatUtils
import com.google.android.accessibility.utils.output.SpeechController
import com.google.common.collect.ImmutableList
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeMonitorTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  private val spoken = mutableListOf<Feedback.Speech>()
  private val pipeline =
    object : Pipeline.FeedbackReturner {
      override fun returnFeedback(feedback: Feedback): Boolean {
        feedback.failovers().first().speech()?.let { spoken += it }
        return true
      }
    }

  private lateinit var audioManager: AudioManager
  private lateinit var monitor: VolumeMonitor

  @Before
  fun setUp() {
    FormFactorUtils.initialize(context)
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    shadowOf(audioManager).setStreamMaxVolume(10)
    // Without this, the first changes also teach how to change the accessibility volume.
    SharedPreferencesUtils.putIntPref(
      SharedPreferencesUtils.getSharedPreferences(context),
      context.resources,
      R.string.pref_a11y_volume_control_hint_times,
      3,
    )
    monitor = VolumeMonitor(pipeline, context, null)
  }

  @Test
  fun eachChangeIsSaidRightAway() {
    change(STREAM_MUSIC, 5, 4)
    change(STREAM_MUSIC, 6, 5)

    assertEquals(
      listOf("Music volume set to 50 percent.", "60 percent"),
      texts(),
    )
    spoken.forEach {
      assertEquals(SpeechController.UTTERANCE_GROUP_VOLUME, it.options()!!.mUtteranceGroup)
      assertEquals(SpeechController.QUEUE_MODE_INTERRUPT, it.options()!!.mQueueMode)
    }
  }

  @Test
  fun aChangeAfterAPauseNamesTheVolumeAgain() {
    change(STREAM_MUSIC, 5, 4)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    change(STREAM_MUSIC, 6, 5)

    assertEquals(
      listOf("Music volume set to 50 percent.", "Music volume set to 60 percent."),
      texts(),
    )
  }

  @Test
  fun aChangeToAnotherVolumeNamesIt() {
    change(STREAM_MUSIC, 5, 4)
    change(STREAM_ACCESSIBILITY, 5, 4)

    assertEquals(
      listOf("Music volume set to 50 percent.", "Accessibility volume set to 50 percent."),
      texts(),
    )
  }

  @Test
  fun continuousReadingIsNotInterrupted() {
    monitor.setContinuousReading { true }

    change(STREAM_MUSIC, 5, 4)

    assertEquals(SpeechController.QUEUE_MODE_QUEUE, spoken.single().options()!!.mQueueMode)
  }

  @Test
  fun aFocusedSliderSaysItsOwnValue() {
    monitor.setSliderFocused { true }

    change(STREAM_MUSIC, 5, 4)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun theVolumeKeysAreSaidOnAFocusedSlider() {
    monitor.setSliderFocused { true }

    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 5, 4)

    assertEquals(listOf("Music volume set to 50 percent."), texts())
  }

  @Test
  fun theMediaVolumeIsSaidWhileMediaPlays() {
    shadowOf(audioManager).setIsMusicActive(true)

    change(STREAM_MUSIC, 5, 4)

    assertEquals(listOf("Music volume set to 50 percent."), texts())
  }

  @Test
  fun theVolumeHeadphonesSetWhenTheyConnectIsNotSaid() {
    monitor.startAnnouncing()
    // Android can report the headphones' level before it says they connected.
    playMediaOn(headphones())

    change(STREAM_MUSIC, 5, 4)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun theHeadphonesRepeatingTheirLevelIsNotSaid() {
    monitor.startAnnouncing()
    playMediaOn(headphones())

    change(STREAM_MUSIC, 5, 4)
    change(STREAM_MUSIC, 5, 5)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun aVolumeKeyAtTheSameLevelIsSaid() {
    monitor.startAnnouncing()
    change(STREAM_MUSIC, 10, 9)
    spoken.clear()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 10, 10)

    assertEquals(listOf("Music volume set to 100 percent."), texts())
  }

  @Test
  fun aVolumeKeyIsSaidOnANewOutput() {
    monitor.startAnnouncing()
    playMediaOn(headphones())

    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 5, 4)

    assertEquals(listOf("Music volume set to 50 percent."), texts())
  }

  @Test
  fun theHeadphoneControlsAreSaidAfterTheHeadphonesSetTheirVolume() {
    monitor.startAnnouncing()
    playMediaOn(headphones())

    change(STREAM_MUSIC, 5, 4)
    change(STREAM_MUSIC, 6, 5)

    assertEquals(listOf("Music volume set to 60 percent."), texts())
  }

  @Test
  fun theSpeakerLevelIsNotSaidWhenHeadphonesDisconnect() {
    monitor.startAnnouncing()
    playMediaOn(headphones())
    change(STREAM_MUSIC, 5, 4)
    playMediaOn(speaker())

    change(STREAM_MUSIC, 3, 5)
    change(STREAM_MUSIC, 4, 3)

    assertEquals(listOf("Music volume set to 40 percent."), texts())
  }

  @Test
  fun theCallAndNotificationLevelsHeadphonesSetWhenTheyConnectAreNotSaid() {
    monitor.startAnnouncing()
    playOn(STREAM_VOICE_CALL, headphones())
    playOn(STREAM_NOTIFICATION, headphones())

    change(STREAM_VOICE_CALL, 9, 1)
    change(STREAM_NOTIFICATION, 3, 9)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun theCallVolumeIsSaidAfterTheHeadphonesSetIt() {
    monitor.startAnnouncing()
    playOn(STREAM_VOICE_CALL, headphones())

    change(STREAM_VOICE_CALL, 9, 1)
    change(STREAM_VOICE_CALL, 8, 9)

    assertEquals(listOf("Phone call volume set to 80 percent."), texts())
  }

  @Test
  fun headphonesConnectedBeforeBacktalkStartsAreNotWaitedFor() {
    playMediaOn(headphones())
    monitor.startAnnouncing()

    change(STREAM_MUSIC, 5, 4)

    assertEquals(listOf("Music volume set to 50 percent."), texts())
  }

  @Test
  fun theAccessibilityVolumeIsKeptWhenItMovesToAnotherOutput() {
    monitor.startAnnouncing()
    monitor.cacheAccessibilityStreamVolume()
    audioManager.setStreamVolume(STREAM_ACCESSIBILITY, 3, 0)

    context.sendBroadcast(
      Intent(AudioManagerCompatUtils.STREAM_DEVICES_CHANGED_ACTION)
        .setPackage(context.packageName)
        .putExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_TYPE, STREAM_ACCESSIBILITY)
    )
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(3, monitor.cachedAccessibilityStreamVolume)
  }

  @Test
  fun nothingMoreIsSaidWhenTheKeysAreStill() {
    change(STREAM_MUSIC, 5, 4)
    spoken.clear()

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun aGestureThatSaysTheVolumeItselfIsNotSpokenOver() {
    var heard = 0
    monitor.addVolumeChangedListener(
      object : VolumeMonitor.VolumeChangedListener {
        override fun onVolumeChanged(type: Int, value: Int, prevValue: Int, percent: Int) {
          heard++
        }

        override fun announcesVolume(type: Int) = type == STREAM_ACCESSIBILITY
      }
    )

    change(STREAM_ACCESSIBILITY, 5, 4)

    assertEquals(1, heard)
    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun aListenerThatDoesNotSayTheVolumeDoesNotSilenceIt() {
    monitor.addVolumeChangedListener { _, _, _, _ -> }

    change(STREAM_ACCESSIBILITY, 5, 4)

    assertEquals(listOf("Accessibility volume set to 50 percent."), texts())
  }

  @Test
  fun aMediaVolumeReadingControlIsNotSpokenOver() {
    monitor.addVolumeChangedListener(
      object : VolumeMonitor.VolumeChangedListener {
        override fun onVolumeChanged(type: Int, value: Int, prevValue: Int, percent: Int) {}

        override fun announcesVolume(type: Int) = type == STREAM_MUSIC
      }
    )

    change(STREAM_MUSIC, 5, 4)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun anAccessibilityVolumeListenerDoesNotSilenceTheMediaVolumeKeys() {
    monitor.addVolumeChangedListener(
      object : VolumeMonitor.VolumeChangedListener {
        override fun onVolumeChanged(type: Int, value: Int, prevValue: Int, percent: Int) {}

        override fun announcesVolume(type: Int) = type == STREAM_ACCESSIBILITY
      }
    )

    change(STREAM_MUSIC, 5, 4)

    assertEquals(listOf("Music volume set to 50 percent."), texts())
  }

  @Test
  fun theAccessibilityVolumeHintIsSaidWhenTheKeysAreStill() {
    SharedPreferencesUtils.putIntPref(
      SharedPreferencesUtils.getSharedPreferences(context),
      context.resources,
      R.string.pref_a11y_volume_control_hint_times,
      0,
    )
    monitor = VolumeMonitor(pipeline, context, null)
    change(STREAM_MUSIC, 5, 4)
    spoken.clear()

    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

    assertEquals(
      listOf(context.getString(R.string.hint_a11y_volume_control)),
      spoken.map { it.hint().toString() },
    )
  }

  @Test
  fun nothingIsSaidWhenSpeakVolumeChangesIsOff() {
    SharedPreferencesUtils.putBooleanPref(
      SharedPreferencesUtils.getSharedPreferences(context),
      context.resources,
      R.string.pref_speak_volume_changes_key,
      false,
    )

    change(STREAM_MUSIC, 5, 4)

    assertEquals(emptyList<String>(), texts())
  }

  @Test
  fun holdingTheKeySaysOnlyTheLevelItEndsAt() {
    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 5, 4)
    key(KeyEvent.ACTION_DOWN, repeat = 1)
    change(STREAM_MUSIC, 6, 5)
    key(KeyEvent.ACTION_DOWN, repeat = 2)
    change(STREAM_MUSIC, 7, 6)

    assertEquals(listOf("Music volume set to 50 percent."), texts())

    key(KeyEvent.ACTION_UP)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    assertEquals(
      listOf("Music volume set to 50 percent.", "70 percent"),
      texts(),
    )
  }

  @Test
  fun aChangeThatArrivesJustAfterTheReleaseIsSaidOnce() {
    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 5, 4)
    key(KeyEvent.ACTION_DOWN, repeat = 1)
    key(KeyEvent.ACTION_UP)
    change(STREAM_MUSIC, 6, 5)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    assertEquals(
      listOf("Music volume set to 50 percent.", "60 percent"),
      texts(),
    )
  }

  @Test
  fun aHoldWithoutAChangeSaysNothingMore() {
    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 10, 9)
    key(KeyEvent.ACTION_DOWN, repeat = 1)
    key(KeyEvent.ACTION_UP)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    assertEquals(listOf("Music volume set to 100 percent."), texts())
  }

  @Test
  fun aPressAfterAHoldIsSaidRightAway() {
    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 5, 4)
    key(KeyEvent.ACTION_DOWN, repeat = 1)
    change(STREAM_MUSIC, 6, 5)
    key(KeyEvent.ACTION_UP)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    key(KeyEvent.ACTION_DOWN, repeat = 0)
    change(STREAM_MUSIC, 7, 6)

    assertEquals(
      listOf(
        "Music volume set to 50 percent.",
        "60 percent",
        "70 percent",
      ),
      texts(),
    )
  }

  private fun key(action: Int, repeat: Int = 0) {
    monitor.onVolumeKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_VOLUME_UP, repeat))
  }

  private fun change(stream: Int, value: Int, prevValue: Int) {
    audioManager.setStreamVolume(stream, value, 0)
    monitor.onReceiveIntent(
      Intent(AudioManagerCompatUtils.VOLUME_CHANGED_ACTION)
        .putExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_TYPE, stream)
        .putExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_VALUE, value)
        .putExtra(AudioManagerCompatUtils.EXTRA_PREV_VOLUME_STREAM_VALUE, prevValue)
    )
    // Runs what is already due, without letting any time pass.
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun headphones(): AudioDeviceInfo =
    AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()

  private fun speaker(): AudioDeviceInfo =
    AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()

  private fun playMediaOn(device: AudioDeviceInfo) {
    playOn(STREAM_MUSIC, device)
  }

  private fun playOn(stream: Int, device: AudioDeviceInfo) {
    shadowOf(audioManager)
      .setAudioDevicesForAttributes(attributesOf(stream), ImmutableList.of(device))
  }

  private fun texts() = spoken.map { it.text().toString() }
}
