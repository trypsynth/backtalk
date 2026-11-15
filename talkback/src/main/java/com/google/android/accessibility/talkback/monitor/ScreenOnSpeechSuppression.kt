/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.PackageNameProvider
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.UserInterface.UserInputEventListener
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.AccessibilityEventUtils
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils.WINDOW_ID_NONE
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.input.WindowEventInterpreter
import com.google.android.accessibility.utils.monitor.DisplayMonitor.DisplayStateChangedListener
import com.google.android.accessibility.utils.monitor.ScreenMonitor

/**
 * A wake can produce several window changes, announcements, and focus events. Keep automatic
 * lock-screen System UI feedback quiet for at most three seconds. Unlocking or interacting ends
 * suppression immediately. Other apps and unknown window owners always keep their normal speech.
 *
 * Separately, System UI's repeated lock-screen message saying where the fingerprint sensor is can
 * be turned off.
 */
class ScreenOnSpeechSuppression(
  private val prefs: SharedPreferences,
  initiallyScreenOn: Boolean,
  private val isWear: Boolean = false,
  private val systemUiPackages: Set<String> = emptySet(),
  private val isLocked: () -> Boolean = { false },
  private val isCallActive: () -> Boolean = { false },
  private val activePackage: () -> CharSequence? = { null },
  private val windowPackage: (Int) -> CharSequence? = { null },
  private val emergencyPackage: () -> String? = { null },
  private val isFingerprintLocation: (String) -> Boolean = { false },
  private val now: () -> Long = { SystemClock.uptimeMillis() },
) : DisplayStateChangedListener, RingerModeAndScreenMonitor.ScreenChangedListener,
  UserInputEventListener {
  private var screenOn = initiallyScreenOn
  private var suppressUntil: Long? = null
  private var receiverContext: Context? = null
  private val unlockReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_USER_PRESENT) onDeviceUnlocked()
    }
  }

  fun registerUnlockReceiver(context: Context) {
    if (receiverContext != null) return
    ContextCompat.registerReceiver(context, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT),
      ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverContext = context
  }

  fun shutdown() {
    receiverContext?.unregisterReceiver(unlockReceiver)
    receiverContext = null
    onUserInteraction()
  }

  override fun onDisplayStateChanged(displayOn: Boolean) {
    if (displayOn != screenOn) {
      screenOn = displayOn
      suppressUntil = if (displayOn && !isWear && isLocked() && !isCallActive() &&
        ScreenAnnouncementSettings.isOn(prefs, ScreenAnnouncementSettings.SUPPRESS_EXTRA_SCREEN_ON_SPEECH)) {
        now() + WAKE_SUPPRESSION_MS
      } else null
    }
  }

  override fun onScreenChanged(isInteractive: Boolean, eventId: EventId?) {
    onDisplayStateChanged(isInteractive)
  }

  fun isSuppressing(): Boolean {
    val deadline = suppressUntil ?: return false
    if (isWear || !isLocked() || isCallActive() || now() >= deadline ||
      !ScreenAnnouncementSettings.isOn(prefs, ScreenAnnouncementSettings.SUPPRESS_EXTRA_SCREEN_ON_SPEECH)) {
      suppressUntil = null
      return false
    }
    return isEligiblePackage(activePackage())
  }

  private fun isEligiblePackage(packageName: CharSequence?): Boolean =
    packageName?.toString() in systemUiPackages && packageName.toString() != emergencyPackage()

  fun onDeviceUnlocked() = onUserInteraction()

  fun onUserInteraction() {
    suppressUntil = null
  }

  override fun touchInteractionState(active: Boolean) {
    if (active) onUserInteraction()
  }

  fun onKeyEvent(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_DOWN &&
      event.keyCode != KeyEvent.KEYCODE_POWER &&
      event.keyCode != KeyEvent.KEYCODE_WAKEUP &&
      event.keyCode != KeyEvent.KEYCODE_SLEEP) {
      onUserInteraction()
    }
  }

  fun onFocusAction(info: FocusActionInfo) {
    when (info.sourceAction) {
      FocusActionInfo.LOGICAL_NAVIGATION, FocusActionInfo.TOUCH_EXPLORATION,
      FocusActionInfo.MANUAL_SCROLL, FocusActionInfo.KEYBOARD_SHORTCUT_REFOCUS -> onUserInteraction()
    }
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    when (event.eventType) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> onUserInteraction()
    }
  }

  /** Focus can arrive without Backtalk's action history; System UI also posts later live updates. */
  fun shouldSuppressEvent(event: AccessibilityEvent): Boolean {
    if (isMutedFingerprintLocation(event)) return true
    if (!isSuppressing()) return false
    val emergency = emergencyPackage()
    if (emergency != null && event.packageName?.toString() == emergency) return false
    if (!isEligiblePackage(event.packageName)) {
      // OEM clock renderers may send focus/content events under their own package, while the
      // actual node and its window belong to System UI. Verify both instead of naming an OEM.
      if (event.eventType !in listOf(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
          AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
        return false
      }
      val node = event.source ?: return false
      if (!isEligiblePackage(node.packageName) || !isEligiblePackage(node.window?.root?.packageName)) {
        return false
      }
    }
    return when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
      AccessibilityEvent.TYPE_ANNOUNCEMENT,
      AccessibilityEvent.TYPE_VIEW_SELECTED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> true
      else -> false
    }
  }

  /**
   * System UI repeats this as an announcement on the lock screen, and one it already scheduled can
   * arrive just after unlocking. The message only ever describes the lock screen, so it is muted
   * whether or not the phone is still locked. Only the exact message is muted, so other fingerprint
   * messages and touched labels still speak.
   */
  private fun isMutedFingerprintLocation(event: AccessibilityEvent): Boolean {
    if (isWear || event.eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT) return false
    if (event.packageName?.toString() !in systemUiPackages) return false
    if (ScreenAnnouncementSettings.isOn(prefs,
        ScreenAnnouncementSettings.LOCK_SCREEN_FINGERPRINT_LOCATION)) return false
    // Match what the announcement rule speaks: the description when there is one, else the text.
    val spoken = AccessibilityEventUtils.getEventTextOrDescription(event) ?: return false
    val text = normalizeMessage(spoken.toString())
    return text.isNotEmpty() && isFingerprintLocation(text)
  }

  /** Only automatic focus in the verified lock-screen package is quiet, never user navigation. */
  fun shouldSuppressFocus(node: AccessibilityNodeInfoCompat, info: FocusActionInfo): Boolean =
    isSuppressing() && isEligiblePackage(node.packageName) && info.sourceAction in listOf(
      FocusActionInfo.SCREEN_STATE_CHANGE, FocusActionInfo.FOCUS_SYNCHRONIZATION,
      FocusActionInfo.ENSURE_ON_SCREEN,
    )

  /** Mixed or unidentified window changes must remain audible, including IME and other apps. */
  fun shouldSuppressWindow(interpretation: WindowEventInterpreter.EventInterpretation): Boolean {
    if (!isSuppressing() || interpretation.shouldAnnounceInputMethodChange ||
      interpretation.isWebDialogOpenedEvent) return false
    val announcement = interpretation.announcement
    if (announcement != null && !isEligiblePackage(announcement.packageName())) return false
    // Match the composer's spoken roles. A disappearing event-source window is often absent
    // during wake; it must not veto suppression of a separately verified System UI title.
    val windows = mutableListOf<WindowEventInterpreter.WindowInterpretation>()
    if (interpretation.mainWindowsChanged) {
      if (interpretation.otherActiveWindow.id != WINDOW_ID_NONE) {
        windows.add(interpretation.otherActiveWindow)
      } else if (interpretation.windowA.id != WINDOW_ID_NONE) {
        windows.add(interpretation.windowA)
        if (interpretation.windowB.id != WINDOW_ID_NONE) windows.add(interpretation.windowB)
      }
    }
    if ((interpretation.mainWindowsChanged || interpretation.picInPicChanged) &&
      interpretation.picInPic.id != WINDOW_ID_NONE &&
      interpretation.otherActiveWindow.id == WINDOW_ID_NONE) {
      windows.add(interpretation.picInPic)
    }
    if (windows.isEmpty() && interpretation.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      interpretation.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) {
      if (interpretation.eventSourceWindow.id == WINDOW_ID_NONE) return false
      windows.add(interpretation.eventSourceWindow)
    }
    if (windows.any { !isEligiblePackage(windowPackage(it.id)) }) return false
    if (interpretation.mainWindowsChanged && windows.isEmpty()) return false
    return announcement != null || windows.isNotEmpty()
  }
  companion object {
    const val WAKE_SUPPRESSION_MS = 3_000L

    /**
     * Samsung's System UI names for the message that says where the fingerprint sensor is, such as
     * "The fingerprint sensor is centered in the lower part of the screen", one per rotation.
     */
    internal val FINGERPRINT_LOCATION_STRINGS =
      listOf(0, 90, 180, 270).map { "kg_in_display_fingerprint_sensor_${it}_help_instructions" }

    // Also no-break and other Unicode spaces, which some languages put in the message.
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")

    /** The same message can differ in line breaks and spacing between the string and the event. */
    internal fun normalizeMessage(text: String): String =
      text.replace(WHITESPACE, " ").trim().lowercase()

    /**
     * Reads the message from System UI in the current language, so no text is hard-coded. They
     * are read again when the language changes. A missing string just leaves the message spoken.
     */
    private fun fingerprintLocationMatcher(
      service: TalkBackService, packages: Set<String>,
    ): (String) -> Boolean {
      var loadedFor: LocaleList? = null
      var messages = emptySet<String>()
      return { text ->
        val locales = service.resources.configuration.locales
        if (locales != loadedFor) {
          loadedFor = locales
          messages = buildSet {
            for (pkg in packages) {
              val res = try {
                service.packageManager.getResourcesForApplication(pkg)
              } catch (_: PackageManager.NameNotFoundException) {
                continue
              }
              for (name in FINGERPRINT_LOCATION_STRINGS) {
                val id = res.getIdentifier(name, "string", pkg)
                if (id != 0) add(normalizeMessage(res.getString(id)))
              }
            }
          }
        }
        text in messages
      }
    }

    /** Android's OEM-overridable configuration identifies the UI/keyguard, not package guesses. */
    internal fun systemUiPackagesFromConfig(read: (String) -> String?): Set<String> = buildSet {
      read("config_systemUi")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
      for (name in listOf("config_systemUIServiceComponent", "config_keyguardComponent")) {
        read(name)?.trim()?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { add(it) }
      }
    }

    @JvmStatic
    fun create(service: TalkBackService, prefs: SharedPreferences, initiallyScreenOn: Boolean,
      isWear: Boolean): ScreenOnSpeechSuppression {
      val systemUiPackages = systemUiPackagesFromConfig { name ->
        val id = service.resources.getIdentifier(name, "string", "android")
        if (id == 0) null else try {
          service.resources.getString(id)
        } catch (_: Resources.NotFoundException) {
          null
        }
      }
      return ScreenOnSpeechSuppression(
        prefs, initiallyScreenOn, isWear,
        systemUiPackages = systemUiPackages,
        isLocked = { ScreenMonitor.isDeviceLocked(service) },
        isCallActive = { service.isPhoneCallActive },
        activePackage = { service.rootInActiveWindow?.packageName },
        windowPackage = { id -> service.windows.firstOrNull { it.id == id }?.root?.packageName },
        emergencyPackage = { PackageNameProvider.getCellBroadcastReceiverPackageName() },
        isFingerprintLocation = fingerprintLocationMatcher(service, systemUiPackages),
      )
    }
    /** All speech paths share the service's wake state, rather than independent one-shot flags. */
    @JvmStatic
    fun from(context: Context): ScreenOnSpeechSuppression? =
      (context as? TalkBackService)?.screenOnSpeechSuppression

  }
}
