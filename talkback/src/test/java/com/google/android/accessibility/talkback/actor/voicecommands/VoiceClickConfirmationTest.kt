/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.content.DialogInterface
import android.os.Looper
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class VoiceClickConfirmationTest {
  private val service = Robolectric.buildService(TalkBackService::class.java).get()

  @Test
  fun activateButtonExplicitlyAcceptsOnceAndDismissalDoesNotAcceptAgain() {
    FormFactorUtils.initialize(service)
    val decisions = mutableListOf<Boolean>()
    val prompt =
      VoiceClickConfirmation(service, Pipeline.FeedbackReturner { true }, "Send") {
        decisions.add(it)
      }
    val dialog = prompt.showDialog()
    assertTrue(dialog.isShowing)
    assertTrue(decisions.isEmpty())
    assertEquals(
      service.getString(R.string.voice_control_activate),
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString(),
    )
    assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled)
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
    shadowOf(Looper.getMainLooper()).idle()
    prompt.dismissDialog()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf(true), decisions)
  }

  @Test
  fun cancelButtonAndDismissingTheDialogAlwaysDecline() {
    FormFactorUtils.initialize(service)
    for (cancelButton in listOf(true, false)) {
      val decisions = mutableListOf<Boolean>()
      val prompt =
        VoiceClickConfirmation(service, Pipeline.FeedbackReturner { true }, "Pay") {
          decisions.add(it)
        }
      val dialog = prompt.showDialog()
      if (cancelButton) dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
      else dialog.cancel()
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(listOf(false), decisions)
    }
  }
}
