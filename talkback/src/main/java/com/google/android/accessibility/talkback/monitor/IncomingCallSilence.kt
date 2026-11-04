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

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.notificationlist.NotificationListService
import com.google.android.accessibility.utils.AccessibilityEventUtils
import com.google.android.accessibility.utils.monitor.RecentUserInput

/**
 * The "Speak incoming calls" setting. When it is off, Backtalk gives no feedback while a call
 * rings: no speech, sounds or vibrations, so not the call notification, the time as the screen turns
 * on, window names, the caller on the call screen, or other apps' notifications. Once the user
 * touches the screen, presses a key other than volume or power, or uses a braille display, Backtalk
 * speaks as usual until the next call.
 *
 * Calls from apps such as WhatsApp count when they ring through Android's phone system, play their
 * ringtone as a ringtone, or show their incoming call notification.
 */
object IncomingCallSilence {
  @JvmStatic @Volatile var speakIncomingCalls = true

  @Volatile private var telephony: TelephonyManager? = null

  @Volatile private var audio: AudioManager? = null

  @Volatile private var keyguard: KeyguardManager? = null

  /** Whether feedback that started before the current call rang was stopped. */
  @Volatile private var interrupted = false

  /**
   * Whether the user touched the screen, pressed a key or used a braille display while the current
   * call rang.
   */
  @Volatile private var actedWhileRinging = false

  /**
   * When an incoming call notification arrived, or 0. On vibrate, an app call gives no other sign
   * that it rings.
   */
  @Volatile private var callNotificationTime = 0L

  /**
   * The app whose incoming call notification [callNotificationTime] is for, or null if unknown. Only
   * its own call notifications end the ring, not another app's ongoing call.
   */
  @Volatile private var callPackage: String? = null

  private enum class Kind {
    OTHER,
    /**
     * A call notification that isn't known to be incoming, such as a phone app's whose call type
     * [isIncoming] can't tell.
     */
    CALL,
    INCOMING_CALL,
  }

  /**
   * The kind of the latest notification. A heads-up is reported just after its notification, so
   * this tells whose heads-up appeared.
   */
  @Volatile private var latestNotification = Kind.OTHER

  /** The kind of notification whose heads-up shows, or null. Only one is tracked at a time. */
  @Volatile private var headsUp: Kind? = null

  /**
   * Whether the ringtone that plays started right after the user's input, such as a preview in the
   * ringtone picker, or null if no ringtone was seen playing.
   */
  @Volatile private var ringtoneFromUser: Boolean? = null

  /** How long a call notification counts as ringing before its heads-up appears. */
  private const val HEADS_UP_WAIT_MS = 3000L

  /** How long a call notification counts as ringing at most, in case its heads-up stays. */
  private const val CALL_NOTIFICATION_MAX_MS = 60_000L

  @JvmStatic
  fun init(context: Context) {
    // The application context, so that this object doesn't keep the service alive after it stops.
    val appContext = context.applicationContext
    telephony = appContext.getSystemService(TelephonyManager::class.java)
    audio = appContext.getSystemService(AudioManager::class.java)
    keyguard = appContext.getSystemService(KeyguardManager::class.java)
  }

  @JvmStatic
  fun onAccessibilityEvent(event: AccessibilityEvent) {
    when (event.eventType) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> onUserInput()
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
        // A toast has no notification, and no heads-up of its own.
        val notification = AccessibilityEventUtils.extractNotification(event) ?: return
        // A missed call notification means the call stopped ringing. This matters on the lock
        // screen, where an app call's notification otherwise counts as ringing for a minute.
        if (notification.category == Notification.CATEGORY_MISSED_CALL) {
          callNotificationTime = 0L
        }
        val kind =
          when {
            notification.category != Notification.CATEGORY_CALL -> Kind.OTHER
            isIncoming(notification) -> Kind.INCOMING_CALL
            else -> Kind.CALL
          }
        latestNotification = kind
        if (kind != Kind.OTHER) {
          val incoming = kind == Kind.INCOMING_CALL
          // A call that starts ringing when nothing rang before is a new call, so input during an
          // earlier call doesn't carry over to it.
          if (incoming && !speakIncomingCalls && !isRinging()) {
            actedWhileRinging = false
            interrupted = false
          }
          // A call notification that isn't incoming, such as an answered call, ends the ring.
          val packageName = event.packageName?.toString()
          if (incoming) {
            callNotificationTime = SystemClock.uptimeMillis()
            callPackage = packageName
          } else if (isRingingApp(packageName)) {
            callNotificationTime = 0L
          }
          // A heads-up that already shows switches to the call, which is reported as no change.
          if (headsUp != null) {
            headsUp = kind
          }
        }
      }
      else -> {}
    }
  }

  /**
   * Ends the ring when the call's notification is removed, or updated once the call is answered.
   * Only the notification list's listener reports these, while it has notification access.
   */
  @JvmStatic
  fun onListenerNotification(
    notification: Notification,
    removed: Boolean,
    packageName: String?,
  ) {
    if (
      notification.category == Notification.CATEGORY_CALL &&
        (removed || !isIncoming(notification)) &&
        isRingingApp(packageName)
    ) {
      callNotificationTime = 0L
    }
  }

  /** Whether an app, when known, is the one whose call rings. */
  private fun isRingingApp(packageName: String?): Boolean {
    val ringing = callPackage
    return packageName == null || ringing == null || packageName == ringing
  }

  /** Keys that silence the ringer or wake the screen, so they don't end the silence. */
  private val KEYS_THAT_KEEP_SILENCE =
    setOf(
      KeyEvent.KEYCODE_VOLUME_DOWN,
      KeyEvent.KEYCODE_VOLUME_UP,
      KeyEvent.KEYCODE_VOLUME_MUTE,
      KeyEvent.KEYCODE_POWER,
      KeyEvent.KEYCODE_WAKEUP,
      KeyEvent.KEYCODE_SLEEP,
    )

  /** Ends the silence for the current call when a key is pressed, other than volume or power. */
  @JvmStatic
  fun onKeyDown(event: KeyEvent) {
    // A held key repeats, and only its first press can end the silence.
    if (event.repeatCount == 0 && event.keyCode !in KEYS_THAT_KEEP_SILENCE) {
      onUserInput()
    }
  }

  /** Ends the silence for the current call, such as when the screen is touched. */
  @JvmStatic
  fun onUserInput() {
    if (isSilenced()) {
      actedWhileRinging = true
    }
  }

  @JvmStatic
  fun onHeadsUpChanged(showing: Boolean) {
    // Another heads-up, such as the missed call's, doesn't keep the call ringing.
    headsUp = if (showing) latestNotification else null
    // Ends the ring now if the call's heads-up has gone.
    isCallNotificationShowing()
  }

  /**
   * Whether feedback that already plays should be stopped, such as what Backtalk was saying when
   * the call started ringing. True once for each silenced call.
   */
  @JvmStatic
  fun takeInterrupt(): Boolean {
    // Checked first, as seeing no call ringing readies the interrupt for the next call.
    if (!isSilenced() || interrupted) {
      return false
    }
    interrupted = true
    return true
  }

  /**
   * Whether a call is ringing, the setting is off, and the user hasn't done anything since the call
   * started ringing. Ringing is checked each time, because the phone state broadcast that
   * [CallStateMonitor] waits for arrives after the screen turns on, and isn't received at all if
   * the phone permission was granted after Backtalk started. Seeing no call ringing also clears the
   * user's input, so that the next call is silenced again.
   */
  @JvmStatic
  fun isSilenced(): Boolean {
    if (speakIncomingCalls) {
      return false
    }
    if (!isRinging()) {
      actedWhileRinging = false
      interrupted = false
      return false
    }
    return !actedWhileRinging
  }

  // The check that needs no call to the system goes first.
  private fun isRinging(): Boolean =
    isCallNotificationShowing() ||
      isPhoneRinging() ||
      // Android's phone system sets this mode while any call it handles rings, app calls included.
      audio?.mode == AudioManager.MODE_RINGTONE ||
      isRingtonePlaying()

  private fun isPhoneRinging(): Boolean =
    try {
      @Suppress("DEPRECATION")
      telephony?.callState == TelephonyManager.CALL_STATE_RINGING
    } catch (e: SecurityException) {
      false
    }

  /**
   * Whether an incoming call notification arrived and still shows. While the notification list has
   * notification access, [onListenerNotification] ends the ring. Otherwise Backtalk isn't told when
   * the notification is removed, so its heads-up has to show. On the lock screen the call shows full
   * screen instead of as a heads-up, so there it counts until a missed call notification arrives.
   */
  private fun isCallNotificationShowing(): Boolean {
    val time = callNotificationTime
    if (time == 0L) {
      return false
    }
    val age = SystemClock.uptimeMillis() - time
    val showing =
      age <= CALL_NOTIFICATION_MAX_MS &&
        (NotificationListService.instance != null ||
          age <= HEADS_UP_WAIT_MS ||
          headsUp == Kind.INCOMING_CALL ||
          keyguard?.isKeyguardLocked == true)
    if (!showing) {
      callNotificationTime = 0L
    }
    return showing
  }

  // Android 12 added the call type, though its names only became public in Android 14.
  @SuppressLint("InlinedApi")
  private fun isIncoming(notification: Notification): Boolean {
    val callType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        notification.extras?.getInt(Notification.EXTRA_CALL_TYPE)
      } else {
        null
      }
    // The call type is exact when set. An ongoing call's notification can keep its full screen
    // intent, so the intent is only a fallback.
    return when (callType) {
      Notification.CallStyle.CALL_TYPE_INCOMING -> true
      Notification.CallStyle.CALL_TYPE_ONGOING -> false
      else -> notification.fullScreenIntent != null
    }
  }

  /**
   * Whether an app plays a ringtone, as calling apps that don't use Android's phone system do. A
   * ringtone first seen within moments of the user's input doesn't count, as it is likely one they
   * chose to hear, such as in the ringtone picker.
   */
  private fun isRingtonePlaying(): Boolean {
    val playing =
      audio?.activePlaybackConfigurations?.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
      } == true
    if (!playing) {
      ringtoneFromUser = null
      return false
    }
    val fromUser = ringtoneFromUser ?: RecentUserInput.isRecent().also { ringtoneFromUser = it }
    return !fromUser
  }
}
