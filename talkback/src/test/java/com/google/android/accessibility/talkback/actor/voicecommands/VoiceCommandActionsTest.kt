/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.Region
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActions.Result
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics
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
@Config(sdk = [34], shadows = [VoiceCommandActionsTest.RefreshingNodes::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceCommandActionsTest {
  /** Framework refresh fetches current view data; Robolectric's default only returns a boolean. */
  @Implements(AccessibilityNodeInfo::class)
  class RefreshingNodes : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var snapshot: AccessibilityNodeInfo

    @Implementation
    override fun refresh(): Boolean {
      val current = live[snapshot] ?: return false
      if (current !== snapshot) {
        snapshot.text = current.text
        snapshot.contentDescription = current.contentDescription
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
    var launchError: RuntimeException? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun startActivity(intent: Intent) {
      launchError?.let { throw it }
      super.startActivity(intent)
    }
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
  private val window = AccessibilityWindowInfo.obtain().also { shadowOf(it).setId(1) }
  private val root = node("", clickable = false)
  private var windows = listOf(VisibleVoiceTarget.Window(1, root, Region(0, 0, 300, 600)))
  private var active = true
  private var focusSucceeds = true
  private var clickSucceeds = true
  private var onFocus: () -> Unit = {}
  private val actions =
    VoiceCommandActions(
      service,
      {
        events.add("focus:${it.text}")
        onFocus()
        focusSucceeds
      },
      {
        events.add("click:${it.text}")
        clickSucceeds
      },
      { active },
      worker,
      Handler(Looper.getMainLooper()),
      { windows },
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
        isEnabled = true
        isVisibleToUser = true
        isClickable = clickable
        setBoundsInScreen(Rect(0, 0, 300, 600))
      }
    shadowOf(raw).setAccessibilityWindowInfo(window)
    RefreshingNodes.live[raw] = raw
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private fun button(text: String): AccessibilityNodeInfoCompat =
    node(text).also {
      shadowOf(root.unwrap()).addChild(it.unwrap())
    }

  private fun app(
    label: String,
    pkg: String = "example.app",
    enabled: Boolean = true,
    activity: String = "$pkg.Main",
  ) {
    val pm = shadowOf(service.packageManager)
    val component = ComponentName(pkg, activity)
    pm.addActivityIfNotPresent(component).apply {
      nonLocalizedLabel = label
      this.enabled = enabled
      exported = true
      applicationInfo.nonLocalizedLabel = label
      applicationInfo.enabled = enabled
    }
    pm.addIntentFilterForActivity(
      component,
      IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
    )
  }

  private fun submit(command: String) {
    actions.handle(command) { results.add(it) }
  }

  private fun finish() {
    worker.runAll()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun anOpenCommandLaunchesTheFuzzyMatchedInstalledApp() {
    app("WhatsApp")
    assertNotNull(actions.findApp("whatsap"))
    submit("open whatsap")
    assertNull(shadowOf(service).nextStartedActivity)
    finish()
    val intent = shadowOf(service).nextStartedActivity
    assertEquals(ComponentName("example.app", "example.app.Main"), intent.component)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertEquals(listOf(Result.RECOGNIZED), results)
    assertTrue(events.isEmpty())
  }

  @Test
  fun multipleLauncherEntriesForTheSameAppAreNotAmbiguous() {
    app("Camera")
    app("Camera", activity = "example.app.LauncherAlias")
    submit("open Camera")
    finish()
    assertEquals("example.app", shadowOf(service).nextStartedActivity.component?.packageName)
    assertEquals(listOf(Result.RECOGNIZED), results)
  }

  @Test
  fun missingAmbiguousAndDisabledAppsNeverFallThroughToOtherCommands() {
    app("Camera", "example.one")
    app("Camera", "example.two")
    app("Disabled", "example.disabled", enabled = false)
    for (command in listOf("open missing home", "open Camera", "open Disabled")) {
      submit(command)
      finish()
    }
    assertEquals(List(3) { Result.UNRECOGNIZED }, results)
    assertNull(shadowOf(service).nextStartedActivity)
    assertTrue(events.isEmpty())
  }

  @Test
  fun anAppThatDisappearsOrCannotBeStartedIsUnrecognized() {
    app("Camera")
    for (error in listOf(ActivityNotFoundException(), SecurityException())) {
      service.launchError = error
      submit("open Camera")
      finish()
    }
    assertEquals(List(2) { Result.UNRECOGNIZED }, results)
    assertNull(shadowOf(service).nextStartedActivity)
  }

  @Test
  fun aVisibleLabelIsFocusedBeforeItIsClicked() {
    button("Save")
    submit("save")
    finish()
    assertEquals(listOf("focus:Save", "click:Save"), events)
    assertEquals(listOf(Result.RECOGNIZED), results)
  }

  @Test
  fun aBareOpenCanClickAButtonNamedOpen() {
    button("Open")
    submit("open")
    finish()
    assertEquals(listOf("focus:Open", "click:Open"), events)
  }

  @Test
  fun aMissingLabelDoesNotClickThePreviouslyFocusedItem() {
    button("Save").isAccessibilityFocused = true
    submit("Missing")
    finish()
    assertTrue(events.isEmpty())
    assertEquals(listOf(Result.TRY_BUILT_IN), results)
  }

  @Test
  fun disappearingItemsAreNotFocusedOrClicked() {
    val save = button("Save")
    submit("save")
    worker.runAll()
    save.isVisibleToUser = false
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(events.isEmpty())
    assertEquals(listOf(Result.UNRECOGNIZED), results)
  }

  @Test
  fun changingTheLabelWhileFocusingCancelsTheClick() {
    val save = button("Save")
    onFocus = { save.text = "Delete" }
    submit("save")
    finish()
    assertEquals(listOf("focus:Save"), events)
    assertEquals(listOf(Result.UNRECOGNIZED), results)
  }

  @Test
  fun failedFocusAndClickAreReportedAsUnrecognized() {
    button("Save")
    focusSucceeds = false
    submit("save")
    finish()
    assertEquals(listOf("focus:Save"), events)
    focusSucceeds = true
    clickSucceeds = false
    submit("save")
    finish()
    assertEquals(List(2) { Result.UNRECOGNIZED }, results)
  }

  @Test
  fun changingWindowsBeforeActivationCancelsTheAction() {
    button("Save")
    submit("save")
    worker.runAll()
    windows = emptyList()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(events.isEmpty())
    assertEquals(listOf(Result.UNRECOGNIZED), results)
  }

  @Test
  fun newerCommandsCancelQueuedResultsAndShutdownCancelsAllActions() {
    button("Save")
    button("Cancel")
    submit("save")
    worker.runAll()
    submit("cancel")
    finish()
    assertEquals(listOf("focus:Cancel", "click:Cancel"), events)
    events.clear()
    submit("save")
    worker.runAll()
    actions.shutdown()
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(events.isEmpty())
  }

  @Test
  fun lockingOrStoppingTheServiceWhileSearchingCancelsTheAction() {
    button("Save")
    submit("save")
    active = false
    finish()
    assertTrue(events.isEmpty())
    assertTrue(results.isEmpty())
  }

  @Test
  fun processorUsesTheExistingUnrecognizedSpeechAndDoesNotActOnPartials() {
    val talkback = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(talkback)
    val feedback = mutableListOf<Feedback>()
    val analytics = object : TalkBackAnalytics {}
    val processor = VoiceCommandProcessor(talkback, null, null, analytics)
    val interpreted = mutableListOf<Interpretation?>()
    processor.setPipelineInterpretationReceiver { _, _, command, _ ->
      interpreted.add(command)
      true
    }
    ReflectionHelpers.setField(processor, "deviceActions", actions)
    ReflectionHelpers.setField(
      processor,
      "pipeline",
      Pipeline.FeedbackReturner {
        feedback.add(it)
        true
      },
    )
    processor.setEchoRecognizedTextEnabled(true)
    processor.setDeviceActionsEnabled(true)

    button("Send")
    assertFalse(processor.handleSpeechCommand("send", true))
    finish()
    assertTrue(events.isEmpty())
    assertTrue(feedback.isEmpty())

    // "Home" inside a failed app request must not trigger the built-in Home action.
    assertTrue(processor.handleSpeechCommand("open missing home", false))
    finish()
    assertEquals(
      talkback.getString(
        R.string.voice_commands_echo_feedback_not_recognized,
        "open missing home",
      ),
      feedback.single().failovers().single().speech()!!.text().toString(),
    )
    feedback.clear()

    assertTrue(processor.handleSpeechCommand("nonexistentbutton", false))
    finish()
    assertEquals(
      talkback.getString(
        R.string.voice_commands_echo_feedback_not_recognized,
        "nonexistentbutton",
      ),
      feedback.single().failovers().single().speech()!!.text().toString(),
    )
    assertTrue(events.isEmpty())
    feedback.clear()
    processor.setEchoRecognizedTextEnabled(false)
    processor.handleSpeechCommand("nonexistentbutton", false)
    finish()
    assertEquals(
      talkback.getString(
        R.string.voice_commands_partial_result,
        talkback.getString(R.string.title_pref_help),
      ),
      feedback.single().failovers().single().speech()!!.text().toString(),
    )
    feedback.clear()
    val home = button("Home")
    processor.handleSpeechCommand("home", false)
    finish()
    assertEquals(listOf("focus:Home", "click:Home"), events)
    assertTrue(interpreted.isEmpty())
    home.isVisibleToUser = false
    processor.handleSpeechCommand("home", false)
    finish()
    assertEquals(
      Interpretation.VoiceCommand.Action.VOICE_COMMAND_HOME,
      (interpreted.single() as Interpretation.VoiceCommand).command(),
    )
  }

  @Test
  @Config(sdk = [26, 34])
  fun actionsAreOffByDefaultAndTurningThemOffCancelsPendingWork() {
    val talkback = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(talkback)
    val analytics = object : TalkBackAnalytics {}
    val processor = VoiceCommandProcessor(talkback, null, null, analytics)
    val interpreted = mutableListOf<Interpretation?>()
    processor.setPipelineInterpretationReceiver { _, _, command, _ ->
      interpreted.add(command)
      true
    }
    ReflectionHelpers.setField(processor, "deviceActions", actions)
    ReflectionHelpers.setField(processor, "pipeline", Pipeline.FeedbackReturner { true })
    val recognizer = SpeechRecognizerPerformer(talkback, 10000, true)
    val actor = VoiceCommandActor(talkback, processor, analytics, recognizer)

    assertFalse(talkback.resources.getBoolean(R.bool.pref_voice_command_device_actions_default))
    button("Home")
    button("Save")
    app("Camera")
    assertTrue(actor.onResult("HOME", true))
    actor.onResult("Open Camera", false)
    actor.onResult("Save", false)
    finish()
    assertEquals(
      Interpretation.VoiceCommand.Action.VOICE_COMMAND_HOME,
      (interpreted.single() as Interpretation.VoiceCommand).command(),
    )
    assertTrue(events.isEmpty())
    assertNull(shadowOf(service).nextStartedActivity)

    actor.setDeviceActionsEnabled(true)
    assertFalse(actor.onResult("Save", true))
    assertTrue(actor.onResult("Save", false))
    worker.runAll()
    actor.setDeviceActionsEnabled(false)
    finish()
    assertTrue(events.isEmpty())

    actor.setDeviceActionsEnabled(true)
    actor.onResult("Save", false)
    finish()
    assertEquals(listOf("focus:Save", "click:Save"), events)
    processor.shutdown()
  }

  @Test
  @Config(sdk = [26, 34])
  fun changingRecognitionPolicyDropsPendingSpeechAndRestoresFastPartials() {
    val recognized = mutableListOf<Pair<String, Boolean>>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, true)
    recognizer.setListener { command, partial ->
      recognized.add(command to partial)
      true
    }
    fun partial(command: String) {
      recognizer.speechRecognitionListener.onPartialResults(
        Bundle().apply {
          putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(command))
        }
      )
    }

    partial("Send")
    recognizer.setPartialResultFastFeedback(false)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertTrue(recognized.isEmpty())

    partial("Open Camera")
    recognizer.setPartialResultFastFeedback(true)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
    assertTrue(recognized.isEmpty())

    partial("Home")
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(listOf("Home" to true), recognized)
  }

  @Test
  @Config(sdk = [26, 34])
  fun recognitionWaitsForTheWholeUtteranceInsteadOfClickingItsFirstWord() {
    val recognized = mutableListOf<Pair<String, Boolean>>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, false)
    recognizer.setListener { command, partial ->
      recognized.add(command to partial)
      true
    }
    recognizer.speechRecognitionListener.onPartialResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Send"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertTrue(recognized.isEmpty())
    recognizer.speechRecognitionListener.onResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Send later"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals(listOf("Send later" to false), recognized)
  }

  @Test
  @Config(sdk = [26, 34])
  fun enginesWithOnlyPartialResultsFinishAtTheEndOfTheSession() {
    val recognized = mutableListOf<Pair<String, Boolean>>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, false)
    recognizer.setListener { command, partial ->
      recognized.add(command to partial)
      true
    }
    recognizer.speechRecognitionListener.onPartialResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Open Camera"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
    assertEquals(listOf("Open Camera" to false), recognized)
  }
}
