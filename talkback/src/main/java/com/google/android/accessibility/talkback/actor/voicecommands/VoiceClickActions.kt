/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.os.Handler
import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.monitor.ScreenMonitor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer

/** Finds a visible app control, asks for confirmation, and rechecks the screen before clicking. */
class VoiceClickActions
internal constructor(
  private val focus: (AccessibilityNodeInfoCompat) -> Boolean,
  private val click: (AccessibilityNodeInfoCompat) -> Boolean,
  private val canAct: () -> Boolean,
  private val worker: ExecutorService,
  private val main: Handler,
  private val windows: (Boolean) -> List<VisibleVoiceTarget.Window>,
  private val confirmWord: String,
  private val cancelWord: String,
  private val showConfirmation: (String, (Boolean) -> Unit) -> (() -> Unit),
) {
  enum class Result {
    CONFIRMATION_SHOWN,
    CLICKED,
    CANCELED,
    UNRECOGNIZED,
  }

  private class Confirmation(
    val target: VisibleVoiceTarget.Target,
    val command: String,
  ) {
    var dismiss: (() -> Unit)? = null
    var expire: Runnable? = null
  }

  constructor(
    service: TalkBackService,
    pipeline: Pipeline.FeedbackReturner,
  ) : this(
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
      pipeline.returnFeedback(
        EVENT_ID_UNTRACKED,
        Feedback.nodeAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK),
      )
    },
    { TalkBackService.isServiceActive() && !ScreenMonitor.isDeviceLocked(service) },
    Executors.newSingleThreadExecutor { task -> Thread(task, "Backtalk-voice-controls") },
    Handler(Looper.getMainLooper()),
    { includeRoots -> VisibleVoiceTarget.windows(service, includeRoots) },
    service.getString(R.string.voice_control_confirm_command),
    service.getString(R.string.voice_control_cancel_command),
    { label, decision ->
      val dialog = VoiceClickConfirmation(service, pipeline, label, decision)
      val dismiss = { dialog.dismissDialog() }
      dialog.showDialog()
      dismiss
    },
  )

  private var pending: Future<*>? = null
  private var confirmation: Confirmation? = null
  private var generation = 0
  private var stopped = false

  /** Called after built-in commands have had the first chance to handle completed speech. */
  fun handle(command: String, completion: Consumer<Result>) {
    if (stopped) return
    val current = confirmation
    if (current != null) {
      when {
        VoiceControlMatching.sameLabel(command, confirmWord) -> {
          decide(current, true, completion)
          return
        }
        VoiceControlMatching.sameLabel(command, cancelWord) -> {
          decide(current, false, completion)
          return
        }
      }
    }
    cancel()
    val request = generation
    find(command, request, completion) { target -> ask(target, command, completion) }
  }

  fun cancel() {
    generation++
    pending?.cancel(true)
    pending = null
    main.removeCallbacksAndMessages(null)
    val old = confirmation
    confirmation = null
    old?.dismiss?.invoke()
  }

  fun shutdown() {
    stopped = true
    cancel()
    worker.shutdownNow()
  }

  private fun isCurrent(request: Int): Boolean = !stopped && request == generation && canAct()

  private fun find(
    command: String,
    request: Int,
    completion: Consumer<Result>,
    found: (VisibleVoiceTarget.Target) -> Unit,
  ) {
    pending = worker.submit {
      val search =
        try {
          VisibleVoiceTarget.find(command, windows(true))
        } catch (_: RuntimeException) {
          VisibleVoiceTarget.Search(failed = true)
        }
      main.post {
        if (isCurrent(request)) {
          val target = search.target
          if (search.failed || target == null) completion.accept(Result.UNRECOGNIZED)
          else found(target)
        }
      }
    }
  }

  private fun focusVisible(target: VisibleVoiceTarget.Target, command: String): Boolean =
    try {
      VisibleVoiceTarget.stillVisible(target, command, windows(false)) &&
        (target.control.isAccessibilityFocused || focus(target.control)) &&
        VisibleVoiceTarget.stillVisible(target, command, windows(false))
    } catch (_: RuntimeException) {
      false
    }

  private fun ask(
    target: VisibleVoiceTarget.Target,
    command: String,
    completion: Consumer<Result>,
  ) {
    if (!focusVisible(target, command)) {
      completion.accept(Result.UNRECOGNIZED)
      return
    }
    val label =
      if (VoiceControlMatching.sameLabel(command, target.label.text)) {
        target.label.text.toString()
      } else target.label.contentDescription.toString()
    val request = Confirmation(target, command)
    confirmation = request
    try {
      request.dismiss =
        showConfirmation(label) { accepted -> decide(request, accepted, completion) }
      if (confirmation !== request) {
        request.dismiss?.invoke()
        return
      }
      request.expire = Runnable { decide(request, false, completion) }
      main.postDelayed(request.expire!!, CONFIRMATION_TIMEOUT_MS)
      completion.accept(Result.CONFIRMATION_SHOWN)
    } catch (_: RuntimeException) {
      cancel()
      completion.accept(Result.UNRECOGNIZED)
    }
  }

  private fun decide(request: Confirmation, accepted: Boolean, completion: Consumer<Result>) {
    if (confirmation !== request) return
    confirmation = null
    request.expire?.let(main::removeCallbacks)
    request.dismiss?.invoke()
    if (!accepted) {
      completion.accept(Result.CANCELED)
      return
    }
    val action = ++generation
    // Let the confirmation window disappear before asking Android for the app's visible region.
    main.postDelayed(
      {
        if (isCurrent(action)) {
          // Search again: a duplicate label or different control may have appeared during the
          // dialog.
          find(request.command, action, completion) { target ->
            val sameTarget =
              target.control == request.target.control &&
                target.windowId == request.target.windowId &&
                target.packageName == request.target.packageName
            val clicked =
              try {
                sameTarget && focusVisible(target, request.command) && click(target.control)
              } catch (_: RuntimeException) {
                false
              }
            completion.accept(if (clicked) Result.CLICKED else Result.UNRECOGNIZED)
          }
        }
      },
      CONFIRMATION_DISMISS_DELAY_MS,
    )
  }

  internal companion object {
    const val CONFIRMATION_TIMEOUT_MS = 30_000L
    const val CONFIRMATION_DISMISS_DELAY_MS = 150L
  }
}
