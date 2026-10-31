/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.dialog.BaseDialog

/** An explicit confirmation for every named control, also usable without another voice command. */
internal class VoiceClickConfirmation(
  service: TalkBackService,
  pipeline: Pipeline.FeedbackReturner,
  label: String,
  private val decide: (Boolean) -> Unit,
) : BaseDialog(service, R.string.voice_control_confirm_title, pipeline) {
  private var decided = false

  init {
    setTitle(service.getString(R.string.voice_control_confirm_title, label))
    setPositiveButtonStringRes(R.string.voice_control_activate)
  }

  override fun handleDialogClick(buttonClicked: Int) {
    if (!decided) {
      decided = true
      decide(buttonClicked == DialogInterface.BUTTON_POSITIVE)
    }
  }

  override fun handleDialogDismiss() {
    if (!decided) {
      decided = true
      decide(false)
    }
  }

  override fun getMessageString(): String =
    context.getString(
      R.string.voice_control_confirm_message,
      context.getString(R.string.voice_control_confirm_command),
      context.getString(R.string.voice_control_cancel_command),
    )

  override fun getCustomizedView(inflater: LayoutInflater): View? = null
}
