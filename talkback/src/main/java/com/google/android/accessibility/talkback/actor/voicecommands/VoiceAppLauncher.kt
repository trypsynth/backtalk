/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer

/** Looks up launchable apps off the speech thread and opens the best match on the main thread. */
class VoiceAppLauncher
internal constructor(
  private val service: AccessibilityService,
  private val canAct: () -> Boolean,
  private val worker: ExecutorService,
  private val main: Handler,
) {
  private data class App(val component: ComponentName, val labels: List<String>)

  constructor(
    service: TalkBackService
  ) : this(
    service,
    { TalkBackService.isServiceActive() && !ScreenMonitor.isDeviceLocked(service) },
    Executors.newSingleThreadExecutor { task -> Thread(task, "Backtalk-voice-apps") },
    Handler(Looper.getMainLooper()),
  )

  private var pending: Future<*>? = null
  private var generation = 0
  private var stopped = false

  /**
   * Returns false for ordinary built-in commands; accepts only completed "Open <name>" requests.
   */
  fun handle(command: String, completion: Consumer<Boolean>): Boolean {
    val name = VoiceAppMatching.appName(command) ?: return false
    if (stopped) return false
    val request = ++generation
    pending?.cancel(true)
    pending = worker.submit {
      val intent =
        try {
          findApp(name)
        } catch (e: RuntimeException) {
          LogUtils.w("VoiceAppLauncher", "App lookup failed: %s", e.javaClass.simpleName)
          null
        }
      main.post {
        if (!stopped && generation == request && canAct()) {
          completion.accept(intent != null && launch(intent))
        }
      }
    }
    return true
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

  internal fun findApp(name: String): Intent? {
    if (name.isEmpty()) return null
    val pm = service.packageManager
    val apps =
      pm
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
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
      VoiceAppMatching.bestApp(name, apps, identity = { it.component.packageName }) { it.labels }
        ?: return null
    return Intent.makeMainActivity(app.component)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
  }

  private fun launch(intent: Intent): Boolean =
    try {
      service.startActivity(intent)
      true
    } catch (_: ActivityNotFoundException) {
      false
    } catch (_: SecurityException) {
      false
    }
}
