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

import android.app.KeyguardManager
import android.app.Notification
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.monitor.RecentUserInput
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rings a call by putting audio in ringtone mode, as Android's phone system does, or only by its
 * call notification and heads-up, as an app call on vibrate does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingCallSilenceTest {
  private lateinit var audio: AudioManager
  private lateinit var keyguard: KeyguardManager

  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    audio = context.getSystemService(AudioManager::class.java)
    keyguard = context.getSystemService(KeyguardManager::class.java)
    shadowOf(keyguard).setKeyguardLocked(false)
    IncomingCallSilence.init(context)
    IncomingCallSilence.speakIncomingCalls = false
    // An answered call and no heads-up end a ring left from another test, and seeing no call
    // ringing clears its input.
    audio.mode = AudioManager.MODE_NORMAL
    post(callNotification(Notification.CallStyle.CALL_TYPE_ONGOING))
    IncomingCallSilence.onHeadsUpChanged(false)
    IncomingCallSilence.isSilenced()
  }

  @After
  fun tearDown() {
    playRingtone(false)
    audio.mode = AudioManager.MODE_NORMAL
    shadowOf(keyguard).setKeyguardLocked(false)
    IncomingCallSilence.speakIncomingCalls = true
  }

  private fun press(keyCode: Int) {
    IncomingCallSilence.onKeyDown(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
  }

  private fun callNotification(callType: Int) =
    Notification().apply {
      category = Notification.CATEGORY_CALL
      extras.putInt(Notification.EXTRA_CALL_TYPE, callType)
    }

  private fun post(notification: Notification, packageName: String? = null) {
    val event = AccessibilityEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
    event.parcelableData = notification
    event.packageName = packageName
    IncomingCallSilence.onAccessibilityEvent(event)
  }

  /** Posts an incoming app call's notification, then shows its heads-up. */
  private fun ringAppCall() {
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    IncomingCallSilence.onHeadsUpChanged(true)
  }

  private fun playRingtone(playing: Boolean) {
    val ringtone =
      AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
    shadowOf(audio).setActivePlaybackConfigurationsFor(if (playing) listOf(ringtone) else listOf())
  }

  private fun passTime(ms: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  }

  @Test
  fun ringingCall_isSilenced() {
    audio.mode = AudioManager.MODE_RINGTONE
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun settingOn_isNotSilenced() {
    IncomingCallSilence.speakIncomingCalls = true
    audio.mode = AudioManager.MODE_RINGTONE
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun key_endsSilence() {
    audio.mode = AudioManager.MODE_RINGTONE
    press(KeyEvent.KEYCODE_TAB)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun volumeKeys_keepSilence() {
    audio.mode = AudioManager.MODE_RINGTONE
    press(KeyEvent.KEYCODE_VOLUME_DOWN)
    press(KeyEvent.KEYCODE_VOLUME_UP)
    press(KeyEvent.KEYCODE_VOLUME_MUTE)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun powerKey_keepsSilence() {
    audio.mode = AudioManager.MODE_RINGTONE
    press(KeyEvent.KEYCODE_POWER)
    press(KeyEvent.KEYCODE_WAKEUP)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun heldKeyRepeat_keepsSilence() {
    audio.mode = AudioManager.MODE_RINGTONE
    val repeat = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 1)
    IncomingCallSilence.onKeyDown(repeat)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun brailleDisplayInput_endsSilence() {
    audio.mode = AudioManager.MODE_RINGTONE
    IncomingCallSilence.onUserInput()
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun inputBeforeTheCall_doesNotEndItsSilence() {
    press(KeyEvent.KEYCODE_TAB)
    audio.mode = AudioManager.MODE_RINGTONE
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun nextCall_isSilencedAgain() {
    audio.mode = AudioManager.MODE_RINGTONE
    press(KeyEvent.KEYCODE_TAB)
    audio.mode = AudioManager.MODE_NORMAL
    assertFalse(IncomingCallSilence.isSilenced())
    audio.mode = AudioManager.MODE_RINGTONE
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_isSilencedWhileItsHeadsUpShows() {
    ringAppCall()
    passTime(10_000)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_endsWhenItsHeadsUpGoes() {
    ringAppCall()
    passTime(5000)
    IncomingCallSilence.onHeadsUpChanged(false)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_waitsForItsHeadsUp() {
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    passTime(1000)
    assertTrue(IncomingCallSilence.isSilenced())
    passTime(2500)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_endsAfterAMinuteEvenIfItsHeadsUpStays() {
    ringAppCall()
    passTime(61_000)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun answeredAppCall_endsTheRing() {
    ringAppCall()
    post(callNotification(Notification.CallStyle.CALL_TYPE_ONGOING))
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun missedCallHeadsUp_doesNotKeepTheCallRinging() {
    ringAppCall()
    passTime(1000)
    IncomingCallSilence.onHeadsUpChanged(false)
    post(Notification().apply { category = Notification.CATEGORY_MISSED_CALL })
    IncomingCallSilence.onHeadsUpChanged(true)
    passTime(2500)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun callArrivingOverAnotherHeadsUp_usesThatHeadsUp() {
    post(Notification().apply { category = Notification.CATEGORY_MESSAGE })
    IncomingCallSilence.onHeadsUpChanged(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    passTime(10_000)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun messageWhileTheCallHeadsUpShows_keepsTheCallRinging() {
    ringAppCall()
    post(Notification().apply { category = Notification.CATEGORY_MESSAGE })
    passTime(10_000)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun inputDuringOneAppCall_doesNotCarryToTheNext() {
    ringAppCall()
    IncomingCallSilence.onUserInput()
    IncomingCallSilence.onHeadsUpChanged(false)
    passTime(4000)
    ringAppCall()
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun toastBeforeTheCallHeadsUp_doesNotHideIt() {
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    IncomingCallSilence.onAccessibilityEvent(
      AccessibilityEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
    )
    IncomingCallSilence.onHeadsUpChanged(true)
    passTime(10_000)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCallOnTheLockScreen_isSilencedWithoutAHeadsUp() {
    shadowOf(keyguard).setKeyguardLocked(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    passTime(30_000)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCallOnTheLockScreen_endsWithItsMissedCall() {
    shadowOf(keyguard).setKeyguardLocked(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    passTime(20_000)
    post(Notification().apply { category = Notification.CATEGORY_MISSED_CALL })
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_endsWhenTheListenerSeesItsNotificationRemoved() {
    shadowOf(keyguard).setKeyguardLocked(true)
    val notification = callNotification(Notification.CallStyle.CALL_TYPE_INCOMING)
    post(notification)
    IncomingCallSilence.onListenerNotification(notification, removed = false, packageName = null)
    assertTrue(IncomingCallSilence.isSilenced())
    IncomingCallSilence.onListenerNotification(notification, removed = true, packageName = null)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCall_endsWhenTheListenerSeesItAnswered() {
    shadowOf(keyguard).setKeyguardLocked(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    IncomingCallSilence.onListenerNotification(
      callNotification(Notification.CallStyle.CALL_TYPE_ONGOING),
      removed = false,
      packageName = null,
    )
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun anotherAppsCall_doesNotEndTheRing() {
    shadowOf(keyguard).setKeyguardLocked(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING), "com.example.calls")
    post(callNotification(Notification.CallStyle.CALL_TYPE_ONGOING), "com.example.meetings")
    IncomingCallSilence.onListenerNotification(
      callNotification(Notification.CallStyle.CALL_TYPE_ONGOING),
      removed = true,
      packageName = "com.example.meetings",
    )
    assertTrue(IncomingCallSilence.isSilenced())
    IncomingCallSilence.onListenerNotification(
      callNotification(Notification.CallStyle.CALL_TYPE_INCOMING),
      removed = true,
      packageName = "com.example.calls",
    )
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun ringtone_isACall() {
    passTime(3000)
    playRingtone(true)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun ringtoneRightAfterInput_isAPreviewNotACall() {
    passTime(3000)
    RecentUserInput.mark()
    playRingtone(true)
    assertFalse(IncomingCallSilence.isSilenced())
    passTime(3000)
    assertFalse(IncomingCallSilence.isSilenced())
    playRingtone(false)
    assertFalse(IncomingCallSilence.isSilenced())
    playRingtone(true)
    assertTrue(IncomingCallSilence.isSilenced())
  }

  @Test
  fun appCallOnTheLockScreen_endsAfterAMinute() {
    shadowOf(keyguard).setKeyguardLocked(true)
    post(callNotification(Notification.CallStyle.CALL_TYPE_INCOMING))
    passTime(61_000)
    assertFalse(IncomingCallSilence.isSilenced())
  }

  @Test
  fun interrupt_isTakenOncePerCall() {
    audio.mode = AudioManager.MODE_RINGTONE
    assertTrue(IncomingCallSilence.takeInterrupt())
    assertFalse(IncomingCallSilence.takeInterrupt())
    audio.mode = AudioManager.MODE_NORMAL
    assertFalse(IncomingCallSilence.takeInterrupt())
    audio.mode = AudioManager.MODE_RINGTONE
    assertTrue(IncomingCallSilence.takeInterrupt())
  }

  @Test
  fun interrupt_isNotTakenOnceTheUserActs() {
    audio.mode = AudioManager.MODE_RINGTONE
    IncomingCallSilence.onUserInput()
    assertFalse(IncomingCallSilence.takeInterrupt())
  }
}
