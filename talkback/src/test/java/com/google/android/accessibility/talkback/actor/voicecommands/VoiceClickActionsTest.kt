/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Region
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceClickActions.Result
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics
import com.google.android.accessibility.talkback.preference.TalkBackPreferenceFilter
import com.google.android.accessibility.talkback.utils.SpeechRecognizerPerformer
import com.google.android.accessibility.utils.FormFactorUtils
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [VoiceClickActionsTest.RefreshingNodes::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceClickActionsTest {
  /** Refresh a saved node from its current view, including clones returned by tree traversal. */
  @Implements(AccessibilityNodeInfo::class)
  class RefreshingNodes : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var snapshot: AccessibilityNodeInfo

    @Implementation
    override fun refresh(): Boolean {
      val current = live[snapshot] ?: return false
      if (current !== snapshot) {
        snapshot.text = current.text
        snapshot.contentDescription = current.contentDescription
        snapshot.packageName = current.packageName
        snapshot.isVisibleToUser = current.isVisibleToUser
        snapshot.isEnabled = current.isEnabled
        snapshot.isClickable = current.isClickable
        snapshot.isEditable = current.isEditable
        snapshot.setBoundsInScreen(Rect().also { current.getBoundsInScreen(it) })
      }
      return true
    }

    companion object {
      val live = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo>()
    }
  }

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  private class Worker : AbstractExecutorService() {
    val tasks = ArrayDeque<Runnable>()
    private var stopped = false

    override fun execute(task: Runnable) {
      tasks.add(task)
    }

    fun runAll() {
      while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun shutdown() {
      stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
      shutdown()
      return tasks.toMutableList().also { tasks.clear() }
    }

    override fun isShutdown() = stopped

    override fun isTerminated() = stopped && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
  }

  private val service = Robolectric.buildService(TestService::class.java).get()
  private val worker = Worker()
  private val results = mutableListOf<Result>()
  private val events = mutableListOf<String>()
  private val prompts = mutableListOf<String>()
  private var decide: ((Boolean) -> Unit)? = null
  private var dismissed = 0
  private var active = true
  private var focusSucceeds = true
  private var clickSucceeds = true
  private var promptThrows = false
  private var onFocus: () -> Unit = {}
  private val window = AccessibilityWindowInfo.obtain().also { shadowOf(it).setId(1) }
  private val root = node("", false)
  private var windows = listOf(VisibleVoiceTarget.Window(1, root, Region(0, 0, 300, 600)))
  private val actions =
    VoiceClickActions(
      {
        events.add("focus:" + it.text)
        onFocus()
        focusSucceeds
      },
      {
        events.add("click:" + it.text)
        clickSucceeds
      },
      { active },
      worker,
      Handler(Looper.getMainLooper()),
      { windows },
      "confirm",
      "cancel",
      { label, decision ->
        if (promptThrows) throw IllegalStateException("Dialog unavailable")
        prompts.add(label)
        decide = decision
        val dismiss: () -> Unit = { dismissed++ }
        dismiss
      },
    )

  @After
  fun close() {
    actions.shutdown()
    RefreshingNodes.live.clear()
  }

  private fun node(text: String, clickable: Boolean = true): AccessibilityNodeInfoCompat {
    val raw =
      AccessibilityNodeInfo.obtain().apply {
        this.text = text
        packageName = "example.app"
        isEnabled = true
        isVisibleToUser = true
        isClickable = clickable
        setBoundsInScreen(Rect(0, 0, 300, 600))
      }
    shadowOf(raw).setAccessibilityWindowInfo(window)
    RefreshingNodes.live[raw] = raw
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private fun button(text: String) =
    node(text).also { shadowOf(root.unwrap()).addChild(it.unwrap()) }

  private fun submit(command: String) {
    actions.handle(command) { results.add(it) }
  }

  private fun finish() {
    worker.runAll()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun finishConfirmation() {
    shadowOf(Looper.getMainLooper())
      .idleFor(Duration.ofMillis(VoiceClickActions.CONFIRMATION_DISMISS_DELAY_MS))
    finish()
  }

  @Test
  fun everyNamedControlIsFocusedAndConfirmedBeforeAnyClick() {
    for (label in listOf("Save", "Delete", "Send", "Pay", "Uninstall")) {
      button(label)
      submit(label)
      finish()
      assertEquals("focus:" + label, events.last())
      assertEquals(label, prompts.last())
      assertFalse(events.contains("click:" + label))
      decide!!(true)
      assertFalse(events.contains("click:" + label))
      finishConfirmation()
      assertEquals("click:" + label, events.last())
      assertEquals(Result.CLICKED, results.last())
    }
  }

  @Test
  fun spokenConfirmationAndCancellationOnlyApplyToThePendingTarget() {
    button("Save")
    submit("Save")
    finish()
    submit("cancel")
    finishConfirmation()
    assertEquals(Result.CANCELED, results.last())
    assertFalse(events.any { it.startsWith("click:") })
    submit("Save")
    finish()
    submit("confirm")
    finishConfirmation()
    assertEquals("click:Save", events.last())
    assertEquals(Result.CLICKED, results.last())
    val clicks = events.count { it.startsWith("click:") }
    submit("confirm")
    finishConfirmation()
    assertEquals(clicks, events.count { it.startsWith("click:") })
    assertEquals(Result.UNRECOGNIZED, results.last())
  }

  @Test
  fun missingNamesNeverPromptOrClickThePreviousFocus() {
    button("Save").isAccessibilityFocused = true
    submit("Missing")
    finish()
    assertTrue(events.isEmpty())
    assertTrue(prompts.isEmpty())
    assertEquals(listOf(Result.UNRECOGNIZED), results)
  }

  @Test
  fun cancelAndExpiryNeverActivateAControl() {
    button("Save")
    submit("Save")
    finish()
    val canceledDecision = decide!!
    canceledDecision(false)
    canceledDecision(true)
    finishConfirmation()
    submit("Save")
    finish()
    val expiredDecision = decide!!
    shadowOf(Looper.getMainLooper())
      .idleFor(Duration.ofMillis(VoiceClickActions.CONFIRMATION_TIMEOUT_MS))
    expiredDecision(true)
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
    assertEquals(2, dismissed)
  }

  @Test
  fun aNewCommandOrShutdownInvalidatesOldConfirmationCallbacks() {
    button("Save")
    button("Cancel changes")
    submit("Save")
    finish()
    val oldDecision = decide!!
    submit("Cancel changes")
    finish()
    oldDecision(true)
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
    val currentDecision = decide!!
    actions.shutdown()
    currentDecision(true)
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun aChangedOrMissingTargetAfterConfirmationIsNotClicked() {
    val save = button("Save")
    submit("Save")
    finish()
    save.text = "Delete"
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    assertFalse(events.any { it.startsWith("click:") })
    save.text = "Save"
    submit("Save")
    finish()
    save.isVisibleToUser = false
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun newlyAmbiguousTargetsAndReusedWindowsAreRejectedAfterConfirmation() {
    button("Save")
    submit("Save")
    finish()
    val other = button("Save")
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    other.isVisibleToUser = false
    submit("Save")
    finish()
    windows = windows.map { it.copy(packageName = "another.app") }
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun changingTheScreenDuringFinalFocusCancelsTheClick() {
    val save = button("Save")
    submit("Save")
    finish()
    onFocus = { save.text = "Delete" }
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun failedFocusPromptOrClickNeverFallsBackToAnotherAction() {
    button("Save")
    focusSucceeds = false
    submit("Save")
    finish()
    assertTrue(prompts.isEmpty())
    focusSucceeds = true
    promptThrows = true
    submit("Save")
    finish()
    promptThrows = false
    clickSucceeds = false
    submit("Save")
    finish()
    decide!!(true)
    finishConfirmation()
    assertEquals(Result.UNRECOGNIZED, results.last())
    assertEquals(1, events.count { it.startsWith("click:") })
  }

  @Test
  fun lockingOrCancelingWhileTheConfirmedLookupIsQueuedPreventsTheClick() {
    button("Save")
    submit("Save")
    finish()
    decide!!(true)
    active = false
    finishConfirmation()
    active = true
    submit("Save")
    finish()
    decide!!(true)
    shadowOf(Looper.getMainLooper())
      .idleFor(Duration.ofMillis(VoiceClickActions.CONFIRMATION_DISMISS_DELAY_MS))
    worker.runAll()
    actions.cancel()
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun builtInCommandsTakePriorityAndCancelAnyPendingConfirmation() {
    val talkback = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(talkback)
    Settings.Secure.putInt(talkback.contentResolver, "user_setup_complete", 1)
    val interpreted = mutableListOf<Interpretation?>()
    val processor = VoiceCommandProcessor(talkback, null, null, object : TalkBackAnalytics {})
    ReflectionHelpers.setField(processor, "clickActions", actions)
    ReflectionHelpers.setField(processor, "pipeline", Pipeline.FeedbackReturner { true })
    processor.setPipelineInterpretationReceiver { _, _, command, _ ->
      interpreted.add(command)
      true
    }
    processor.setClickControlsEnabled(true)
    val builtInLabels =
      listOf(
        R.string.voice_commands_home,
        R.string.voice_commands_back,
        R.string.voice_commands_talkback_settings,
      )
    for (label in builtInLabels.map(talkback::getString)) {
      button(label)
      assertTrue(processor.handleSpeechCommand(label, false))
      finish()
      assertTrue("Built-in command searched for a control: $label", events.isEmpty())
    }
    assertTrue(prompts.isEmpty())
    assertEquals(
      listOf(
        Interpretation.VoiceCommand.Action.VOICE_COMMAND_HOME,
        Interpretation.VoiceCommand.Action.VOICE_COMMAND_BACK,
      ),
      interpreted.map { (it as Interpretation.VoiceCommand).command() },
    )
    assertNotNull(shadowOf(talkback).nextStartedActivity)
    button("Save")
    processor.handleSpeechCommand("Save", false)
    finish()
    val oldDecision = decide!!
    processor.handleSpeechCommand("Home", false)
    oldDecision(true)
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
  }

  @Test
  fun theDefaultOffSettingAndPartialsCannotTriggerNamedClicks() {
    val talkback = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(talkback)
    val feedback = mutableListOf<Feedback>()
    val processor = VoiceCommandProcessor(talkback, null, null, object : TalkBackAnalytics {})
    ReflectionHelpers.setField(processor, "clickActions", actions)
    ReflectionHelpers.setField(
      processor,
      "pipeline",
      Pipeline.FeedbackReturner {
        feedback.add(it)
        true
      },
    )
    processor.setEchoRecognizedTextEnabled(true)
    assertFalse(talkback.resources.getBoolean(R.bool.pref_voice_click_controls_default))
    button("Send")
    processor.handleSpeechCommand("Send", false)
    finish()
    assertTrue(prompts.isEmpty())
    processor.setClickControlsEnabled(true)
    assertFalse(processor.handleSpeechCommand("Send", true))
    finish()
    assertTrue(prompts.isEmpty())
    processor.handleSpeechCommand("Send", false)
    finish()
    assertEquals(listOf("Send"), prompts)
    assertFalse(processor.handleSpeechCommand("confirm", true))
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
    val oldDecision = decide!!
    processor.setClickControlsEnabled(false)
    oldDecision(true)
    finishConfirmation()
    assertFalse(events.any { it.startsWith("click:") })
    feedback.clear()
    processor.setClickControlsEnabled(true)
    processor.handleSpeechCommand("Missing", false)
    finish()
    assertEquals(
      talkback.getString(R.string.voice_commands_echo_feedback_not_recognized, "Missing"),
      feedback.single().failovers().single().speech()!!.text().toString(),
    )
  }

  @Test
  fun theSettingIsHiddenOnWatchesAndTelevisions() {
    for ((watch, mode) in
      listOf(
        false to Configuration.UI_MODE_TYPE_NORMAL,
        true to Configuration.UI_MODE_TYPE_NORMAL,
        false to Configuration.UI_MODE_TYPE_TELEVISION,
      )) {
      shadowOf(service.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, watch)
      shadowOf(service.getSystemService(UiModeManager::class.java)).setCurrentModeType(mode)
      FormFactorUtils.initialize(service)
      val screen = PreferenceManager(service).createPreferenceScreen(service)
      val key = service.getString(R.string.pref_voice_click_controls_key)
      screen.addPreference(Preference(service).apply { this.key = key })
      TalkBackPreferenceFilter(service).filterPreferences(screen)
      assertEquals(
        !watch && mode != Configuration.UI_MODE_TYPE_TELEVISION,
        screen.findPreference<Preference>(key) != null,
      )
    }
  }

  @Test
  @Config(sdk = [26, 34])
  fun recognitionNeverConfirmsFromAnEarlyPartialResult() {
    val recognized = mutableListOf<String>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, true)
    recognizer.setListener { command, _ ->
      recognized.add(command)
      true
    }
    recognizer.requireFinalResults(VoiceClickActions::class.java, true)
    recognizer.speechRecognitionListener.onPartialResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("confirm"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertTrue(recognized.isEmpty())
    recognizer.speechRecognitionListener.onResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("cancel"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    assertEquals(listOf("cancel"), recognized)
  }

  @Test
  @Config(sdk = [26, 34])
  fun recognitionErrorsAndTimeoutsCannotConfirmAnUnfinishedPhrase() {
    for (timesOut in listOf(false, true)) {
      val recognized = mutableListOf<String>()
      val errors = mutableListOf<Int>()
      val recognizer = SpeechRecognizerPerformer(service, 10000, true)
      recognizer.setListener(
        object : SpeechRecognizerPerformer.SpeechRecognizerRequester {
          override fun onResult(command: String, isPartialResult: Boolean): Boolean {
            recognized.add(command)
            return true
          }

          override fun onError(error: Int) {
            errors.add(error)
          }
        }
      )
      recognizer.requireFinalResults(VoiceClickActions::class.java, true)
      recognizer.speechRecognitionListener.onPartialResults(
        Bundle().apply {
          putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("confirm"))
        }
      )
      if (timesOut) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
      else recognizer.speechRecognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
      shadowOf(Looper.getMainLooper()).idle()
      assertTrue(recognized.isEmpty())
      assertEquals(
        listOf(
          if (timesOut) SpeechRecognizer.ERROR_SPEECH_TIMEOUT else SpeechRecognizer.ERROR_NO_MATCH
        ),
        errors,
      )
    }
  }
}
