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

package com.google.android.accessibility.talkback.directtouch

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Region
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Feedback.PassThroughMode.Action.DIRECT_TOUCH_REGION
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.monitor.RingerModeAndScreenMonitor
import com.google.android.accessibility.utils.AccessibilityWindowInfoUtils.WINDOW_ID_NONE
import com.google.android.accessibility.utils.FeatureSupport
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.input.WindowEventInterpreter
import com.google.android.accessibility.utils.input.WindowEventInterpreter.EventInterpretation
import com.google.android.accessibility.utils.monitor.DisplayMonitor
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE
import com.google.android.accessibility.utils.output.FeedbackItem.FLAG_NO_HISTORY
import com.google.android.accessibility.utils.output.SpeechController.QUEUE_MODE_QUEUE
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.accessibility.utils.output.ThemeVibrations

/**
 * Gives games direct touch. While an app the user turned on is in front, touches go straight to it.
 * Touch goes back to Backtalk as soon as a dialog, another app, the notification shade or a text
 * field needs the screen reader, or the screen turns off. The keyboard stays with Backtalk without
 * ending direct touch, because its window is cut out of the passthrough region unless direct typing
 * is on for the app.
 *
 * Everything comes from Backtalk's own window, screen and display monitors, so there is no tree
 * scan. Turning direct touch off takes effect at once. Turning it on waits [DEBOUNCE_MS] so that a
 * burst of window changes settles first. All callbacks arrive on the main thread.
 */
class DirectTouchController(
  private val service: AccessibilityService,
  private val prefs: SharedPreferences,
  private val feedback: Pipeline.FeedbackReturner,
) :
  WindowEventInterpreter.WindowEventHandler,
  RingerModeAndScreenMonitor.ScreenChangedListener,
  DisplayMonitor.DisplayStateChangedListener {

  private val handler = Handler(Looper.getMainLooper())
  private val reevaluate = Runnable { evaluate() }
  private val recheck = Runnable { evaluate() }

  private var mainWindowId = WINDOW_ID_NONE
  private var textFieldFocused = false
  private var screenInteractive = true
  private var displayOn = true
  private var active = false
  private var paused = false

  // The system holds preference listeners weakly, so this must stay a field.
  private val prefsListener =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key != null && key.startsWith("pref_direct_touch")) {
        evaluate()
      }
    }

  init {
    DirectTouchSettings.migrateNavBarSetting(
      prefs,
      service.getString(R.string.pref_lift_to_activate_key),
    )
    prefs.registerOnSharedPreferenceChangeListener(prefsListener)
  }

  override fun handle(interpretation: EventInterpretation, eventId: EventId?) {
    if (!FeatureSupport.supportPassthrough()) {
      return
    }
    mainWindowId = interpretation.windowA.id
    if (interpretation.mainWindowsChanged) {
      textFieldFocused = false
    }
    scheduleEvaluate()
  }

  /**
   * Raw events, before Backtalk drops any. Watches for window changes, on or off, and for a text
   * field taking focus.
   */
  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!FeatureSupport.supportPassthrough()) {
      return
    }
    if (DirectTouchRegions.reassertsRegion(event.eventType)) {
      // Look again once things settle, like the original did after every window change. The active
      // window moves without Backtalk's window interpreter reporting it, for instance back to the
      // game when a finger lifts off the navigation bar, and nothing else would turn direct touch
      // back on. While on, this also sends the region again after another service clears it.
      handler.removeCallbacks(recheck)
      handler.postDelayed(recheck, DEBOUNCE_MS)
    }
    if (!active) {
      return
    }
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
        if (focused != textFieldFocused) {
          textFieldFocused = focused
          scheduleEvaluate()
        }
      }
    }
  }

  override fun onScreenChanged(isInteractive: Boolean, eventId: EventId?) {
    screenInteractive = isInteractive
    evaluate()
  }

  override fun onDisplayStateChanged(displayOn: Boolean) {
    this.displayOn = displayOn
    evaluate()
  }

  /**
   * Stops sending the region while Backtalk is paused, when explore by touch is off and the region
   * does nothing. On resume, sends it again for the current screen.
   */
  fun setPaused(paused: Boolean) {
    this.paused = paused
    handler.removeCallbacks(reevaluate)
    handler.removeCallbacks(recheck)
    if (!paused) {
      evaluate()
    }
  }

  fun shutdown() {
    prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    handler.removeCallbacks(reevaluate)
    handler.removeCallbacks(recheck)
    if (active) {
      active = false
      clearRegion()
    }
  }

  private fun scheduleEvaluate() {
    handler.removeCallbacks(reevaluate)
    if (active) {
      // Handing back must not wait, and neither must a region that changed under us.
      evaluate()
    } else {
      handler.postDelayed(reevaluate, DEBOUNCE_MS)
    }
  }

  private fun evaluate() {
    handler.removeCallbacks(reevaluate)
    if (paused || !FeatureSupport.supportPassthrough()) {
      return
    }
    val windows = service.windows.orEmpty()
    val mainPackage =
      windows.firstOrNull { it.id == mainWindowId }?.root?.packageName?.toString()
    val directTyping = mainPackage != null && DirectTouchSettings.isDirectTyping(prefs, mainPackage)
    val shouldBeActive = mainPackage != null && shouldBeActive(windows, mainPackage)
    if (shouldBeActive) {
      applyRegion(windows, directTyping)
    }
    if (shouldBeActive == active) {
      return
    }
    active = shouldBeActive
    if (!active) {
      clearRegion()
    }
    announce(active)
  }

  private fun shouldBeActive(
    windows: List<AccessibilityWindowInfo>,
    mainPackage: String,
  ): Boolean {
    if (mainPackage in IGNORED_PACKAGES) {
      return false
    }
    val capable = DirectTouchCapability.declaresDirectTouch(service.packageManager, mainPackage)
    DirectTouchSettings.onFirstSight(prefs, mainPackage, capable)
    // A dialog is a window of its own, so any other application window is either a dialog from the
    // same app or another app.
    val otherApplicationWindows =
      windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id != mainWindowId }
    val input =
      DirectTouchInput(
        masterEnabled = DirectTouchSettings.isMasterEnabled(prefs),
        appEnabled = DirectTouchSettings.isAppEnabled(prefs, mainPackage),
        screenInteractive = screenInteractive && displayOn,
        foreignAppWindow =
          otherApplicationWindows.any { it.root?.packageName?.toString() != mainPackage },
        systemUiCoversHalf = systemUiCoversHalf(windows),
        dialogShowing = otherApplicationWindows.isNotEmpty(),
        textFieldFocused = textFieldFocused,
      )
    return DirectTouchPolicy.shouldBeActive(input)
  }

  private fun systemUiCoversHalf(windows: List<AccessibilityWindowInfo>): Boolean {
    val displayHeight = displayBounds().height()
    val bounds = Rect()
    return windows.any { window ->
      if (
        window.type != AccessibilityWindowInfo.TYPE_SYSTEM ||
          window.root?.packageName?.toString() != SYSTEM_UI
      ) {
        return@any false
      }
      window.getBoundsInScreen(bounds)
      DirectTouchPolicy.coversHalfScreen(window.isActive, bounds.height(), displayHeight)
    }
  }

  private fun applyRegion(windows: List<AccessibilityWindowInfo>, directTyping: Boolean) {
    val excluded = mutableListOf<Rect>()
    windows.forEach { window ->
      if (DirectTouchRegions.shouldExcludeWindow(window.type, directTyping)) {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        excluded += bounds
      }
    }
    sendRegion(DirectTouchRegions.passthroughRegion(displayBounds(), excluded))
  }

  private fun clearRegion() = sendRegion(Region())

  private fun sendRegion(region: Region) {
    feedback.returnFeedback(
      Performance.EVENT_ID_UNTRACKED,
      Feedback.passThroughMode(DIRECT_TOUCH_REGION, region),
    )
  }

  private fun displayBounds(): Rect =
    Rect(service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds)

  private fun announce(on: Boolean) {
    if (DirectTouchSettings.isSpeechEnabled(prefs)) {
      val text = service.getString(if (on) R.string.direct_touch_on else R.string.direct_touch_off)
      // A game is always playing audio, and speech is dropped while audio plays unless it is forced.
      val options =
        SpeakOptions.create()
          .setQueueMode(QUEUE_MODE_QUEUE)
          .setFlags(
            FLAG_NO_HISTORY or
              FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE or
              FLAG_FORCE_FEEDBACK_EVEN_IF_MICROPHONE_ACTIVE
          )
      feedback.returnFeedback(Performance.EVENT_ID_UNTRACKED, Feedback.speech(text, options))
    }
    if (DirectTouchSettings.isHapticsEnabled(prefs)) {
      val vibrator = service.getSystemService(Vibrator::class.java)
      val themeName = if (on) THEME_ON else THEME_OFF
      if (vibrator == null || !ThemeVibrations.play(vibrator, themeName, VIBRATION_ATTRIBUTES)) {
        vibrate(if (on) ON_PATTERN else OFF_PATTERN)
      }
    }
  }

  /**
   * Vibrates directly. The switch in the direct touch settings is an explicit choice, so it does not
   * depend on Backtalk's vibration feedback setting, which drops every Feedback.vibration when off.
   */
  private fun vibrate(pattern: LongArray) {
    val vibrator = service.getSystemService(Vibrator::class.java)
    if (vibrator == null || !vibrator.hasVibrator()) {
      return
    }
    @Suppress("DEPRECATION") // The attributes overload is the one that reaches API 26.
    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), VIBRATION_ATTRIBUTES)
  }

  private companion object {
    const val DEBOUNCE_MS = 150L
    const val SYSTEM_UI = "com.android.systemui"
    // The names a sound theme replaces the on and off vibrations by.
    const val THEME_ON = "direct_touch_on"
    const val THEME_OFF = "direct_touch_off"
    val ON_PATTERN = longArrayOf(0, 15, 100, 15)
    val OFF_PATTERN = longArrayOf(0, 40)
    val VIBRATION_ATTRIBUTES: AudioAttributes =
      AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .build()
    val IGNORED_PACKAGES = setOf(SYSTEM_UI, "android", "com.google.android.gms")
  }
}
