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

package com.google.android.accessibility.talkback.feedbackpolicy

import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.compositor.EarlyFocusSpeech
import com.google.android.accessibility.talkback.compositor.EventFilter
import com.google.android.accessibility.talkback.compositor.EventInterpretation
import com.google.android.accessibility.talkback.compositor.GlobalVariables
import com.google.android.accessibility.talkback.eventprocessor.ProcessorPhoneticLetters
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.monitor.ScreenAnnouncementSettings
import com.google.android.accessibility.talkback.monitor.ScreenOnSpeechSuppression
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.input.WindowEventInterpreter
import com.google.android.accessibility.utils.monitor.CollectionState
import com.google.android.accessibility.utils.monitor.InputModeTracker
import com.google.android.accessibility.utils.monitor.TouchMonitor
import com.google.android.accessibility.utils.output.SpeechCacheManager.LoadSpeechResultNotifier
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenOnSuppressionTest {
  class TestService : TalkBackService() {
    lateinit var suppression: ScreenOnSpeechSuppression
    override fun getScreenOnSpeechSuppression(): ScreenOnSpeechSuppression = suppression
  }

  private lateinit var service: TestService
  private lateinit var composer: ScreenFeedbackManager.FeedbackComposer
  private var locked = true
  private var callActive = false
  private var foreground: String? = "com.android.systemui"
  private var elapsed = 0L
  private var emergency: String? = "example.cellbroadcast"
  private var uiPackages = setOf("com.android.systemui")
  private val windowPackages = mutableMapOf<Int, String>()

  private fun policy(initiallyOn: Boolean = false) = ScreenOnSpeechSuppression(
    SharedPreferencesUtils.getSharedPreferences(service), initiallyOn,
    systemUiPackages = uiPackages,
    isLocked = { locked }, isCallActive = { callActive }, activePackage = { foreground },
    windowPackage = { windowPackages[it] }, emergencyPackage = { emergency }, now = { elapsed },
  )

  @Before
  fun setUp() {
    service = Robolectric.buildService(TestService::class.java).get()
    FormFactorUtils.initialize(service)
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear().commit()
    service.suppression =
      policy()
    composer = ScreenFeedbackManager.FeedbackComposer(service, null, null)
  }

  private fun suppress(enabled: Boolean) {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(ScreenAnnouncementSettings.SUPPRESS_EXTRA_SCREEN_ON_SPEECH, enabled).commit()
  }

  private fun announcement(wake: Boolean): WindowEventInterpreter.EventInterpretation =
    WindowEventInterpreter.EventInterpretation().apply {
      setInterpretFirstTimeWhenWakeUp(wake)
      setAnnouncement(
        WindowEventInterpreter.Announcement.create(
          "Carrier, Extend Unlock", "com.android.systemui", false, false
        )
      )
    }

  @Test
  fun laterWakeAnnouncementsAreAlsoSuppressedUntilInteraction() {
    assertFalse(composer.composeFeedback(announcement(true), 0).isEmpty)
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(composer.composeFeedback(announcement(true), 0).isEmpty)
    // Samsung sends more window announcements after the first interpretation is consumed.
    assertTrue(composer.composeFeedback(announcement(false), 0).isEmpty)
    service.suppression.onUserInteraction()
    assertFalse(composer.composeFeedback(announcement(false), 0).isEmpty)
    service.suppression.onDisplayStateChanged(false)
    service.suppression.onDisplayStateChanged(true)
    suppress(false)
    assertFalse(composer.composeFeedback(announcement(true), 0).isEmpty)
  }

  @Test
  fun automaticFocusRemainsMutedAfterTheFirstWakeFlagIsConsumed() {
    val node = AccessibilityNodeInfoCompat.obtain().apply { packageName = "com.android.systemui" }
    val restored = FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build()
    assertFalse(service.suppression.shouldSuppressFocus(node, restored))
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    for (source in listOf(FocusActionInfo.SCREEN_STATE_CHANGE, FocusActionInfo.FOCUS_SYNCHRONIZATION,
      FocusActionInfo.ENSURE_ON_SCREEN)) {
      val info = FocusActionInfo.builder().setSourceAction(source).build()
      assertTrue(service.suppression.shouldSuppressFocus(node, info))
      node.packageName = "example.cellbroadcast"
      assertFalse(service.suppression.shouldSuppressFocus(node, info))
      node.packageName = "com.android.systemui"
    }
    // User navigation clears suppression before its feedback is composed, including braille.
    service.suppression.onFocusAction(
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    )
    assertFalse(service.suppression.shouldSuppressFocus(node, restored))
    // A later wake is independently suppressed.
    service.suppression.onDisplayStateChanged(false)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(service.suppression.shouldSuppressFocus(node, restored))
  }

  private fun rawEvent(type: Int, packageName: String = "com.android.systemui") =
    AccessibilityEvent.obtain(type).apply { this.packageName = packageName }

  @Test
  fun rawLockScreenAnnouncementsAndUnrecordedFocusAreSuppressed() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    val suppression = service.suppression
    assertTrue(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)))
    assertTrue(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)))
    assertTrue(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)))
    assertFalse(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)))
    assertFalse(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT, "example.app")))
    suppression.onAccessibilityEvent(rawEvent(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
    assertFalse(suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)))
    assertFalse(composer.composeFeedback(announcement(false), 0).isEmpty)
  }

  @Test
  fun carrierTextDoesNotAffectSuppressionAndVendorAppsKeepTheirAnnouncements() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    for (label in listOf("Example Mobile", "Emergency calls only", "オペレーター", "")) {
      for (type in listOf(
        AccessibilityEvent.TYPE_ANNOUNCEMENT,
        AccessibilityEvent.TYPE_VIEW_SELECTED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      )) {
        val event = rawEvent(type).apply { text.add(label) }
        assertTrue(service.suppression.shouldSuppressEvent(event))
      }
      // An unverified vendor lock-screen app must keep its speech.
      val window = WindowEventInterpreter.EventInterpretation().apply {
        setAnnouncement(WindowEventInterpreter.Announcement.create(label, "example.vendor.lockscreen", false, false))
      }
      assertFalse(service.suppression.shouldSuppressWindow(window))
    }
    service.suppression.onUserInteraction()
    val selection = rawEvent(AccessibilityEvent.TYPE_VIEW_SELECTED).apply { text.add("Example Mobile") }
    assertFalse(service.suppression.shouldSuppressEvent(selection))
  }

  @Test
  fun oemUiAndKeyguardPackagesComeFromConfigurationAndMissingConfigAllowsSpeech() {
    val config = mapOf("config_systemUi" to "vendor.ui",
      "config_systemUIServiceComponent" to "vendor.ui/.SystemService",
      "config_keyguardComponent" to "vendor.lock/.KeyguardService")
    uiPackages = ScreenOnSpeechSuppression.systemUiPackagesFromConfig { config[it] }
    assertEquals(setOf("vendor.ui", "vendor.lock"), uiPackages)
    service.suppression = policy()
    foreground = "vendor.lock"
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(service.suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "vendor.lock")))
    assertFalse(service.suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "com.android.systemui")))
    uiPackages = ScreenOnSpeechSuppression.systemUiPackagesFromConfig { if (it == "config_keyguardComponent") "invalid" else null }
    assertTrue(uiPackages.isEmpty())
    service.suppression = policy()
    service.suppression.onDisplayStateChanged(true)
    assertFalse(service.suppression.isSuppressing())
  }

  @Test
  fun aRendererPackageCannotMakeSystemUiClockFocusSpeakTwice() {
    val root = AccessibilityNodeInfo.obtain().apply { packageName = "com.android.systemui" }
    val clock = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.android.systemui"
      className = "android.widget.TextView"
      text = "Clock sample"
      isVisibleToUser = true
      isAccessibilityFocused = true
    }
    val window = AccessibilityWindowInfo.obtain()
    shadowOf(window).setRoot(root)
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_SYSTEM)
    shadowOf(clock).setAccessibilityWindowInfo(window)
    // The sender is a separate renderer; neither its name nor the clock's text drives policy.
    val event = rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "example.clock.renderer")
    shadowOf(event).setSourceNode(clock)
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(service.suppression.shouldSuppressEvent(event))
    event.eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    assertTrue(service.suppression.shouldSuppressEvent(event))
    event.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
    assertFalse(service.suppression.shouldSuppressEvent(event))
    event.eventType = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
    assertFalse(service.suppression.shouldSuppressEvent(event))
    event.eventType = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
    event.packageName = "example.cellbroadcast"
    assertFalse(service.suppression.shouldSuppressEvent(event))
    event.packageName = "example.timer"
    clock.packageName = "example.timer"
    root.packageName = "example.timer"
    assertFalse(service.suppression.shouldSuppressEvent(event))
    clock.packageName = "com.android.systemui"
    assertFalse(service.suppression.shouldSuppressEvent(event))
    root.packageName = "com.android.systemui"
    service.suppression.onUserInteraction()
    assertFalse(service.suppression.shouldSuppressEvent(event))
  }

  @Test
  fun delayedCarrierAndUnlockAnnouncementsDoNotReachTheSpeechOutput() {
    val globals = GlobalVariables(service, InputModeTracker(), CollectionState(), null)
    val compositor = Compositor(
      service, null, null, globals, ProcessorPhoneticLetters(service, globals), Compositor.FLAVOR_NONE
    )
    val spoken = mutableListOf<String>()
    compositor.setSpeaker(object : Compositor.Speaker {
      override fun speak(text: CharSequence, eventId: EventId?, options: SpeakOptions) {
        spoken.add(text.toString())
      }

      override fun addSpeech(
        text: CharSequence,
        eventId: EventId?,
        speechParams: Bundle,
        statusNotifier: LoadSpeechResultNotifier,
      ) {
        spoken.add(text.toString())
      }
    })
    val filter = EventFilter(service, compositor, TouchMonitor(), globals, EarlyFocusSpeech())
    val extendUnlock = rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply { text.add("Extend Unlock") }
    val carrier = rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply { text.add("Freedom") }
    val carrierNode = AccessibilityNodeInfo.obtain().apply {
      className = "android.widget.TextView"
      text = "Freedom"
      isSelected = true
    }
    val carrierMarquee = rawEvent(AccessibilityEvent.TYPE_VIEW_SELECTED).apply { text.add("Freedom") }
    shadowOf(carrierMarquee).setSourceNode(carrierNode)
    // SelectionEventInterpreter sends this directly through Mappers, bypassing EventFilter.
    val selection = EventInterpretation(Compositor.EVENT_TYPE_VIEW_SELECTED)
    compositor.handleEvent(carrierMarquee, null, selection)
    assertEquals(listOf("Freedom"), spoken)
    spoken.clear()
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    filter.sendEvent(extendUnlock, null)
    filter.sendEvent(carrier, null)
    compositor.handleEvent(carrierMarquee, null, selection)
    assertTrue(spoken.isEmpty())
    val appAnnouncement = rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT, "example.app").apply {
      text.add("Download finished")
    }
    filter.sendEvent(appAnnouncement, null)
    assertEquals(listOf("Download finished"), spoken)
    service.suppression.onUserInteraction()
    filter.sendEvent(extendUnlock, null)
    filter.sendEvent(carrier, null)
    compositor.handleEvent(carrierMarquee, null, selection)
    assertEquals(listOf("Download finished", "Extend Unlock", "Freedom", "Freedom"), spoken)
  }

  @Test
  fun duplicateScreenOnSignalsDoNotMuteTheFirstNavigationOrItsWindow() {
    suppress(true)
    val suppression = service.suppression
    suppression.onDisplayStateChanged(true)
    suppression.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER))
    assertTrue(suppression.isSuppressing())
    suppression.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
    suppression.onScreenChanged(true, null)
    suppression.onDisplayStateChanged(true)
    assertFalse(suppression.isSuppressing())
    assertFalse(composer.composeFeedback(announcement(false), 0).isEmpty)
    suppression.onDisplayStateChanged(false)
    suppression.onDisplayStateChanged(true)
    assertTrue(suppression.isSuppressing())
    suppression.touchInteractionState(true)
    assertFalse(suppression.isSuppressing())
  }

  @Test
  fun timeoutIsBoundedAndDuplicateWakeDoesNotExtendIt() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    elapsed = 2_999L
    assertTrue(service.suppression.isSuppressing())
    service.suppression.onDisplayStateChanged(true)
    elapsed = 3_000L
    assertFalse(service.suppression.isSuppressing())
    assertFalse(composer.composeFeedback(announcement(false), 0).isEmpty)
    service.suppression.onDisplayStateChanged(false)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(service.suppression.isSuppressing())
  }

  @Test
  fun unlockWithoutTouchEndsSuppressionAndRelockingDoesNotRearmIt() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    locked = false
    assertFalse(service.suppression.isSuppressing())
    locked = true
    assertFalse(service.suppression.isSuppressing())
    service.suppression.onDisplayStateChanged(false)
    service.suppression.onDisplayStateChanged(true)
    service.suppression.registerUnlockReceiver(service)
    service.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertFalse(service.suppression.isSuppressing())
    service.suppression.shutdown()
    service.suppression.shutdown()
    service.suppression.onDisplayStateChanged(false)
    locked = false
    service.suppression.onDisplayStateChanged(true)
    assertFalse(service.suppression.isSuppressing())
  }

  @Test
  fun otherAppsAndUnknownOwnersKeepFocusAndWindowSpeech() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build()
    for (owner in listOf("example.incall", "example.clock", "example.timer", "example.cellbroadcast", null)) {
      val node = AccessibilityNodeInfoCompat.obtain().apply { packageName = owner }
      assertFalse(service.suppression.shouldSuppressFocus(node, info))
      val event = rawEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED).apply { packageName = owner }
      assertFalse(service.suppression.shouldSuppressEvent(event))
      val window = WindowEventInterpreter.EventInterpretation().apply {
        setAnnouncement(WindowEventInterpreter.Announcement.create("Alert", owner, false, false))
      }
      assertFalse(service.suppression.shouldSuppressWindow(window))
      assertFalse(composer.composeFeedback(window, 0).isEmpty)
      val appWindow = WindowEventInterpreter.EventInterpretation().apply {
        setMainWindowsChanged(true)
        windowA.setId(10)
        windowA.setTitleForFeedback("Alarm or emergency alert")
      }
      if (owner == null) windowPackages.remove(10) else windowPackages[10] = owner
      assertFalse(composer.composeFeedback(appWindow, 0).isEmpty)
      foreground = owner
      assertFalse(service.suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)))
      foreground = "com.android.systemui"
    }
    // Even a receiver configured to use System UI must never have its emergency speech muted.
    emergency = "com.android.systemui"
    assertFalse(service.suppression.shouldSuppressEvent(rawEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)))
  }

  @Test
  fun incomingCallEndsSuppressionEvenIfSystemUiStaysInFront() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    callActive = true
    assertFalse(service.suppression.isSuppressing())
    callActive = false
    assertFalse(service.suppression.isSuppressing())
    service.suppression.onDisplayStateChanged(false)
    callActive = true
    service.suppression.onDisplayStateChanged(true)
    assertFalse(service.suppression.isSuppressing())
  }

  @Test
  fun mixedWindowsAndKeyboardChangesAreNotSuppressed() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    val window = WindowEventInterpreter.EventInterpretation().apply {
      setMainWindowsChanged(true)
      windowA.setId(1)
    }
    assertFalse(service.suppression.shouldSuppressWindow(window))
    windowPackages[1] = "com.android.systemui"
    assertTrue(service.suppression.shouldSuppressWindow(window))
    // Real wake trace: System UI is spoken, but an unrelated removed source has no root.
    window.eventSourceWindow.setId(77)
    assertTrue(service.suppression.shouldSuppressWindow(window))
    windowPackages[77] = "example.biometrics"
    assertTrue(service.suppression.shouldSuppressWindow(window))
    window.otherActiveWindow.setId(77)
    assertFalse(service.suppression.shouldSuppressWindow(window))
    window.otherActiveWindow.setId(-1)
    window.windowB.setId(2)
    windowPackages[2] = "example.clock"
    assertFalse(service.suppression.shouldSuppressWindow(window))
    windowPackages[2] = "com.android.systemui"
    window.picInPic.setId(3)
    windowPackages[3] = "example.timer"
    assertFalse(service.suppression.shouldSuppressWindow(window))
    window.picInPic.setId(-1)
    window.setShouldAnnounceInputMethodChange(true)
    assertFalse(service.suppression.shouldSuppressWindow(window))
  }

  @Test
  fun anUnknownOrEmergencyPaneTitleStillSpeaks() {
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    val pane = WindowEventInterpreter.EventInterpretation().apply {
      setEventType(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
      setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED)
      eventSourceWindow.setId(88)
      eventSourceWindow.setTitleForFeedback("Emergency alert")
    }
    assertFalse(service.suppression.shouldSuppressWindow(pane))
    assertFalse(composer.composeFeedback(pane, 0).isEmpty)
    windowPackages[88] = "example.cellbroadcast"
    assertFalse(service.suppression.shouldSuppressWindow(pane))
    assertFalse(composer.composeFeedback(pane, 0).isEmpty)
    windowPackages[88] = "com.android.systemui"
    assertTrue(service.suppression.shouldSuppressWindow(pane))
  }

  @Test
  fun suppressionPreferenceIsVisibleOnPhonesAndHiddenOnWear() {
    for (wear in listOf(false, true)) {
      shadowOf(service.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH, wear)
      FormFactorUtils.initialize(service)
      val manager = androidx.preference.PreferenceManager(service)
      val screen = manager.createPreferenceScreen(service)
      val preference = androidx.preference.SwitchPreference(service).apply {
        key = ScreenAnnouncementSettings.SUPPRESS_EXTRA_SCREEN_ON_SPEECH
      }
      screen.addPreference(preference)
      com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter(service)
        .filterPreferences(screen)
      assertEquals(if (wear) 0 else 1, screen.preferenceCount)
    }
  }

  @Test
  fun startingTheServiceWithTheScreenOnDoesNotSilenceNavigation() {
    suppress(true)
    val suppression = policy(initiallyOn = true)
    suppression.onDisplayStateChanged(true)
    assertFalse(suppression.isSuppressing())
    suppression.onDisplayStateChanged(false)
    suppression.onDisplayStateChanged(true)
    assertTrue(suppression.isSuppressing())
  }
}
