/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.view.accessibility.AccessibilityEvent
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAppLauncherTest {
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
  private var active = true
  private val results = mutableListOf<Boolean>()
  private val launcher =
    VoiceAppLauncher(service, { active }, worker, Handler(Looper.getMainLooper()))

  @After
  fun close() {
    launcher.shutdown()
  }

  private fun app(
    label: String,
    pkg: String = "example.app",
    activity: String = pkg + ".Main",
    enabled: Boolean = true,
  ) {
    val component = ComponentName(pkg, activity)
    val pm = shadowOf(service.packageManager)
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

  private fun submit(command: String) = launcher.handle(command) { results.add(it) }

  private fun finish() {
    worker.runAll()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun opensTheBestAppOnlyAfterTheBackgroundLookup() {
    app("WhatsApp")
    assertTrue(submit("Open whatsap"))
    assertNull(shadowOf(service).nextStartedActivity)
    finish()
    val intent = shadowOf(service).nextStartedActivity
    assertEquals(ComponentName("example.app", "example.app.Main"), intent.component)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertEquals(listOf(true), results)
  }

  @Test
  fun aliasesForOneAppAreAllowedButDifferentAppsWithTheSameNameAreAmbiguous() {
    app("Camera")
    app("Camera", activity = "example.app.Alias")
    submit("Open Camera")
    finish()
    assertEquals("example.app", shadowOf(service).nextStartedActivity.component?.packageName)
    app("Camera", pkg = "example.other")
    submit("Open Camera")
    finish()
    assertNull(shadowOf(service).nextStartedActivity)
    assertEquals(listOf(true, false), results)
  }

  @Test
  fun missingAndDisabledAppsAndLaunchFailuresAreUnrecognized() {
    app("Disabled", enabled = false)
    app("Camera")
    for (command in listOf("Open Missing", "Open Disabled")) {
      submit(command)
      finish()
    }
    for (error in listOf(ActivityNotFoundException(), SecurityException())) {
      service.launchError = error
      submit("Open Camera")
      finish()
    }
    assertEquals(List(4) { false }, results)
    assertNull(shadowOf(service).nextStartedActivity)
  }

  @Test
  fun nonAppCommandsAreLeftToTheExistingHandler() {
    for (command in listOf("Home", "Back", "Open", "Settings")) assertFalse(submit(command))
    finish()
    assertTrue(results.isEmpty())
  }

  @Test
  fun newerRequestsCancellationShutdownAndLockingPreventStaleLaunches() {
    app("Camera")
    submit("Open Camera")
    worker.runAll()
    submit("Open Missing")
    finish()
    assertEquals(listOf(false), results)
    submit("Open Camera")
    worker.runAll()
    launcher.cancel()
    finish()
    active = false
    submit("Open Camera")
    finish()
    active = true
    submit("Open Camera")
    worker.runAll()
    launcher.shutdown()
    finish()
    assertNull(shadowOf(service).nextStartedActivity)
    assertEquals(listOf(false), results)
  }

  @Test
  fun processorIsOptInKeepsBuiltInsAndUsesTheExistingErrorMessage() {
    val talkback = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(talkback)
    val feedback = mutableListOf<Feedback>()
    val interpreted = mutableListOf<Interpretation?>()
    val processor = VoiceCommandProcessor(talkback, null, null, object : TalkBackAnalytics {})
    ReflectionHelpers.setField(processor, "appLauncher", launcher)
    ReflectionHelpers.setField(
      processor,
      "pipeline",
      Pipeline.FeedbackReturner {
        feedback.add(it)
        true
      },
    )
    processor.setPipelineInterpretationReceiver { _, _, command, _ ->
      interpreted.add(command)
      true
    }
    processor.setEchoRecognizedTextEnabled(true)
    app("Camera")
    assertFalse(talkback.resources.getBoolean(R.bool.pref_voice_open_apps_default))
    processor.handleSpeechCommand("Open Camera", false)
    finish()
    assertNull(shadowOf(service).nextStartedActivity)
    feedback.clear()
    processor.setOpenAppsEnabled(true)
    assertFalse(processor.handleSpeechCommand("Open Camera", true))
    processor.handleSpeechCommand("Open missing home", false)
    finish()
    assertTrue(interpreted.isEmpty())
    assertEquals(
      talkback.getString(R.string.voice_commands_echo_feedback_not_recognized, "Open missing home"),
      feedback.single().failovers().single().speech()!!.text().toString(),
    )
    processor.handleSpeechCommand("Home", false)
    assertEquals(
      Interpretation.VoiceCommand.Action.VOICE_COMMAND_HOME,
      (interpreted.single() as Interpretation.VoiceCommand).command(),
    )
    processor.handleSpeechCommand("Open Camera", false)
    worker.runAll()
    processor.setOpenAppsEnabled(false)
    finish()
    assertNull(shadowOf(service).nextStartedActivity)
  }

  @Test
  fun settingIsVisibleOnPhonesAndHiddenOnWatchesAndTelevisions() {
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
      val key = service.getString(R.string.pref_voice_open_apps_key)
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
  fun recognitionWaitsForFinalSpeechAndIndependentRequirementsDoNotOverrideEachOther() {
    val recognized = mutableListOf<Pair<String, Boolean>>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, true)
    recognizer.setListener { command, partial ->
      recognized.add(command to partial)
      true
    }
    recognizer.requireFinalResults("apps", true)
    recognizer.requireFinalResults("another feature", true)
    recognizer.requireFinalResults("apps", false)
    recognizer.speechRecognitionListener.onPartialResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Open YouTube"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertTrue(recognized.isEmpty())
    recognizer.speechRecognitionListener.onResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Open YouTube Music"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    assertEquals(listOf("Open YouTube Music" to false), recognized)
    recognizer.requireFinalResults("another feature", false)
    recognizer.speechRecognitionListener.onPartialResults(
      Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Home"))
      }
    )
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
    assertEquals("Home" to true, recognized.last())
  }

  @Test
  @Config(sdk = [26, 34])
  fun partialOnlyEnginesFinishAtTimeoutAndChangingThePolicyCancelsPendingSpeech() {
    val recognized = mutableListOf<String>()
    val recognizer = SpeechRecognizerPerformer(service, 10000, true)
    recognizer.setListener { command, _ ->
      recognized.add(command)
      true
    }
    recognizer.requireFinalResults("apps", true)
    fun partial() =
      recognizer.speechRecognitionListener.onPartialResults(
        Bundle().apply {
          putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Open Camera"))
        }
      )
    partial()
    recognizer.requireFinalResults("apps", false)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
    assertTrue(recognized.isEmpty())
    recognizer.requireFinalResults("apps", true)
    partial()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
    assertEquals(listOf("Open Camera"), recognized)
  }
}
