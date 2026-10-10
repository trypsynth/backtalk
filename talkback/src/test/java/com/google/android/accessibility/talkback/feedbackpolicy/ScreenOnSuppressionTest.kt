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
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.helper.FocusActorHelper
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.compositor.EarlyFocusSpeech
import com.google.android.accessibility.talkback.compositor.EventFilter
import com.google.android.accessibility.talkback.compositor.EventInterpretation
import com.google.android.accessibility.talkback.compositor.GlobalVariables
import com.google.android.accessibility.talkback.eventprocessor.ProcessorPhoneticLetters
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState
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

  @Before
  fun setUp() {
    service = Robolectric.buildService(TestService::class.java).get()
    FormFactorUtils.initialize(service)
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear().commit()
    service.suppression =
      ScreenOnSpeechSuppression(SharedPreferencesUtils.getSharedPreferences(service), false)
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
    val node = AccessibilityNodeInfoCompat.obtain()
    val state = ScreenState(null, null, 0, true)
    assertFalse(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, state))
    suppress(true)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, state))
    // A failed restoration must allow the next automatic focus strategy to use the same policy.
    assertTrue(state.isInterpretFirstTimeWhenWakeUp)
    assertTrue(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, state))
    state.consumeInterpretFirstTimeWhenWakeUp()
    assertTrue(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, state))
    assertTrue(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, null))
    // User navigation clears suppression before its feedback is composed, including braille.
    service.suppression.onFocusAction(
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    )
    assertFalse(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, state))
    assertFalse(FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, null))
    // A later wake is independently suppressed.
    service.suppression.onDisplayStateChanged(false)
    service.suppression.onDisplayStateChanged(true)
    assertTrue(
      FocusActorHelper.shouldMuteFeedbackForFocusedNode(service, node, ScreenState(null, null, 1, true))
    )
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
  fun wakeSuppressionDoesNotDependOnCarrierTextOrWindowAnnouncementPackage() {
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
      // Window feedback is suppressed even when a manufacturer uses another package.
      val window = WindowEventInterpreter.EventInterpretation().apply {
        setAnnouncement(WindowEventInterpreter.Announcement.create(label, "example.vendor.lockscreen", false, false))
      }
      assertTrue(composer.composeFeedback(window, 0).isEmpty)
    }
    service.suppression.onUserInteraction()
    val selection = rawEvent(AccessibilityEvent.TYPE_VIEW_SELECTED).apply { text.add("Example Mobile") }
    assertFalse(service.suppression.shouldSuppressEvent(selection))
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
    val suppression = ScreenOnSpeechSuppression(SharedPreferencesUtils.getSharedPreferences(service), true)
    suppression.onDisplayStateChanged(true)
    assertFalse(suppression.isSuppressing())
    suppression.onDisplayStateChanged(false)
    suppression.onDisplayStateChanged(true)
    assertTrue(suppression.isSuppressing())
  }
}
