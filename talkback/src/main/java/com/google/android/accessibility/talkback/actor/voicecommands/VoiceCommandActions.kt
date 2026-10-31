/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer

/** Runs device lookups off the speech/gesture thread, then focuses and acts on the main thread. */
class VoiceCommandActions
internal constructor(
  private val service: AccessibilityService,
  private val focus: (AccessibilityNodeInfoCompat) -> Boolean,
  private val click: (AccessibilityNodeInfoCompat) -> Boolean,
  private val canAct: () -> Boolean,
  private val worker: ExecutorService,
  private val main: Handler,
  private val windows: (Boolean) -> List<VisibleVoiceTarget.Window> = { includeRoots ->
    VisibleVoiceTarget.windows(service, includeRoots)
  },
) {
  enum class Result {
    RECOGNIZED,
    UNRECOGNIZED,
    TRY_BUILT_IN,
  }

  private data class App(val component: ComponentName, val labels: List<String>)

  constructor(
    service: TalkBackService,
    pipeline: Pipeline.FeedbackReturner,
  ) : this(
    service,
    { node ->
      pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.focus(
          node,
          FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
        ),
      )
    },
    { node ->
      // An explicit node action cannot fall back to tapping a previously focused item.
      pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.nodeAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK),
      )
    },
    { TalkBackService.isServiceActive() && !ScreenMonitor.isDeviceLocked(service) },
    Executors.newSingleThreadExecutor { task -> Thread(task, "Backtalk-voice-actions") },
    Handler(Looper.getMainLooper()),
  )

  private var pending: Future<*>? = null
  private var generation = 0
  private var stopped = false

  /** Accepts only completed speech, so a partial label such as "Send" cannot click too early. */
  fun handle(command: String, completion: Consumer<Result>) {
    if (stopped) return
    val request = ++generation
    pending?.cancel(true)
    pending = worker.submit {
      val name = VoiceCommandMatching.appName(command)
      try {
        if (name != null) {
          val intent = findApp(name)
          main.post {
            if (isCurrent(request)) {
              completion.accept(
                if (intent != null && launch(intent)) Result.RECOGNIZED else Result.UNRECOGNIZED
              )
            }
          }
        } else {
          val search = VisibleVoiceTarget.find(command, windows(true))
          main.post {
            if (isCurrent(request)) {
              val result =
                when {
                  search.failed -> Result.UNRECOGNIZED
                  search.target == null -> Result.TRY_BUILT_IN
                  activate(search.target, command) -> Result.RECOGNIZED
                  else -> Result.UNRECOGNIZED
                }
              completion.accept(result)
            }
          }
        }
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "Voice command lookup failed: %s", e.javaClass.simpleName)
        main.post { if (isCurrent(request)) completion.accept(Result.UNRECOGNIZED) }
      }
    }
  }

  fun cancel() {
    generation++
    pending?.cancel(true)
    pending = null
    main.removeCallbacksAndMessages(null)
  }

  fun shutdown() {
    stopped = true
    cancel()
    worker.shutdownNow()
  }

  private fun isCurrent(request: Int): Boolean = !stopped && generation == request && canAct()

  internal fun findApp(name: String): Intent? {
    if (name.isEmpty()) return null
    val pm = service.packageManager
    val apps =
      pm
        .queryIntentActivities(
          Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
          0,
        )
        .mapNotNull { info ->
          val activity = info.activityInfo ?: return@mapNotNull null
          if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled) {
            return@mapNotNull null
          }
          App(
            ComponentName(activity.packageName, activity.name),
            listOf(
              info.loadLabel(pm).toString(),
              activity.applicationInfo.loadLabel(pm).toString(),
            ),
          )
        }
        .distinctBy { it.component }
    val app =
      VoiceCommandMatching.bestApp(name, apps, identity = { it.component.packageName }) {
        it.labels
      } ?: return null
    return Intent.makeMainActivity(app.component)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
  }

  private fun launch(intent: Intent): Boolean =
    try {
      service.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false // The app may have been uninstalled since the query.
    } catch (_: SecurityException) {
      false
    }

  private fun activate(target: VisibleVoiceTarget.Target, command: String): Boolean =
    try {
      if (!VisibleVoiceTarget.stillVisible(target, command, windows(false))) {
        false
      } else if (!target.control.isAccessibilityFocused && !focus(target.control)) {
        false
      } else {
        // Focusing can change an app's screen. Check its label and visible bounds again before
        // click.
        VisibleVoiceTarget.stillVisible(target, command, windows(false)) && click(target.control)
      }
    } catch (_: RuntimeException) {
      false
    }

  private companion object {
    const val TAG = "VoiceCommandActions"
  }
}
