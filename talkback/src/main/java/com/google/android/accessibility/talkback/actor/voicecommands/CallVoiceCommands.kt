/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.google.android.accessibility.talkback.actor.voicecommands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor.RECOGNITION_SPEECH_DELAY_MS
import com.google.android.accessibility.talkback.permission.PermissionUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Voice commands for phone calls: "call Mom", "call Mom on speaker", "answer the call", "decline",
 * "hang up". A call is always read back and confirmed with "yes" first. It uses only Android's own
 * call APIs, which need the phone, contacts and answer-calls permissions.
 */
class CallVoiceCommands(private val context: Context) {

  private sealed interface Pending {
    /** Waiting for "yes" before dialing [number], which is read out as [label]. */
    class Confirm(val label: String, val number: String, val speaker: Boolean) : Pending

    /** Waiting for the user to choose one of [options]. */
    class Choose(val options: List<CallContact>, val speaker: Boolean) : Pending
  }

  private val handler = Handler(Looper.getMainLooper())
  private var pipeline: Pipeline.FeedbackReturner? = null
  private var pending: Pending? = null
  private val expirePending = Runnable { pending = null }

  fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
    this.pipeline = pipeline
  }

  /** Returns true when [normalized] was a call request, or the answer to one. */
  fun handleCommand(normalized: String, isPartialResult: Boolean): Boolean {
    // Only a final result counts: a partial "call mo" must not dial, or read a half-heard "yes".
    if (isPartialResult) return false

    pending?.let {
      answer(it, normalized)
      return true
    }

    when (val command = CallCommandParser.parse(normalized) ?: return false) {
      is CallCommand.Dial -> dial(command)
      CallCommand.Answer -> answerCall()
      CallCommand.Decline -> endCall(R.string.voice_commands_call_declining)
      CallCommand.HangUp -> endCall(R.string.voice_commands_call_hanging_up)
    }
    return true
  }

  // --- Placing a call ---

  private fun dial(command: CallCommand.Dial) {
    val number = CallCommandParser.toPhoneNumber(command.target)
    val needed =
      if (number != null) listOf(Manifest.permission.CALL_PHONE)
      else listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
    if (!requirePermissions(needed)) return

    if (number != null) {
      confirm(Pending.Confirm(spellOut(number), number, command.speaker))
      return
    }
    // A big address book takes a moment; keep the main thread free.
    Thread {
        val matches = CallContactMatcher.match(command.target, readContacts())
        handler.post { onContactsMatched(command, matches) }
      }
      .start()
  }

  private fun onContactsMatched(command: CallCommand.Dial, matches: List<CallContact>) {
    when (matches.size) {
      0 ->
        speak(
          context.getString(R.string.voice_commands_call_contact_not_found, command.target), false)
      1 -> confirm(Pending.Confirm(matches[0].name, matches[0].number, command.speaker))
      else -> {
        val list =
          matches.withIndex().joinToString(" ") { (i, c) ->
            context.getString(R.string.voice_commands_call_choose_item, i + 1, c.name)
          }
        pending = Pending.Choose(matches, command.speaker)
        startExpiry()
        speak(
          context.getString(
            R.string.voice_commands_call_choose, matches.size, command.target, list),
          true)
      }
    }
  }

  private fun answer(waiting: Pending, normalized: String) {
    handler.removeCallbacks(expirePending)
    pending = null
    when (waiting) {
      is Pending.Confirm -> {
        if (CallCommandParser.isYes(normalized)) {
          place(waiting)
        } else {
          speak(context.getString(R.string.voice_commands_call_cancelled), false)
        }
      }
      is Pending.Choose -> {
        val choice = CallCommandParser.parseChoice(normalized, waiting.options.size)
        if (choice == null) {
          speak(context.getString(R.string.voice_commands_call_cancelled), false)
        } else {
          val contact = waiting.options[choice - 1]
          confirm(Pending.Confirm(contact.name, contact.number, waiting.speaker))
        }
      }
    }
  }

  private fun confirm(request: Pending.Confirm) {
    pending = request
    startExpiry()
    speak(
      context.getString(
        if (request.speaker) R.string.voice_commands_call_confirm_speaker
        else R.string.voice_commands_call_confirm,
        request.label),
      true)
  }

  private fun place(request: Pending.Confirm) {
    val telecom = context.getSystemService(TelecomManager::class.java)
    val extras = Bundle()
    if (request.speaker) {
      extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
    }
    try {
      telecom.placeCall(Uri.fromParts("tel", request.number, null), extras)
      speak(context.getString(R.string.voice_commands_call_calling, request.label), false)
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not place call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    }
  }

  // --- Incoming and ongoing calls ---

  private fun answerCall() {
    if (!requirePermissions(listOf(Manifest.permission.ANSWER_PHONE_CALLS))) return
    val telecom = context.getSystemService(TelecomManager::class.java)
    try {
      speak(context.getString(R.string.voice_commands_call_answering), false)
      telecom.acceptRingingCall()
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not answer call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    }
  }

  /** Ends the ongoing call, or turns away a ringing one. */
  private fun endCall(announcement: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      speak(context.getString(R.string.voice_commands_call_needs_newer_android), false)
      return
    }
    if (!requirePermissions(listOf(Manifest.permission.ANSWER_PHONE_CALLS))) return
    val telecom = context.getSystemService(TelecomManager::class.java)
    try {
      @Suppress("DEPRECATION") val ended = telecom.endCall()
      speak(
        context.getString(if (ended) announcement else R.string.voice_commands_call_no_call), false)
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not end call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    }
  }

  // --- Helpers ---

  /**
   * Says [text]. With [thenListen], the microphone opens again once the speech is over, so the
   * user can answer "say yes to confirm" without another gesture.
   */
  private fun speak(text: CharSequence, thenListen: Boolean) {
    val returner = pipeline ?: return
    val speakOptions =
      SpeakOptions.create().apply {
        setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
        if (thenListen) {
          setCompletedAction {
            handler.postDelayed(
              {
                returner.returnFeedback(
                  EVENT_ID_UNTRACKED,
                  Feedback.voiceRecognition(Feedback.VoiceRecognition.Action.START_LISTENING, false))
              },
              LISTEN_AGAIN_DELAY_MS)
          }
        }
      }
    returner.returnFeedback(
      EVENT_ID_UNTRACKED,
      Feedback.speech(text, speakOptions).setDelayMs(RECOGNITION_SPEECH_DELAY_MS))
  }

  /**
   * Returns true when every permission in [permissions] is granted. Otherwise asks for the missing
   * ones in one request, and tells the user aloud what to do.
   */
  private fun requirePermissions(permissions: List<String>): Boolean {
    val missing =
      permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
      }
    if (missing.isEmpty()) return true
    speak(context.getString(R.string.voice_commands_call_permission_needed), false)
    PermissionUtils.requestPermissions(context, *missing.toTypedArray())
    return false
  }

  /** Every phone number in the address book, each person's preferred number first. */
  private fun readContacts(): List<CallContact> {
    val columns = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
    val contacts = mutableListOf<CallContact>()
    try {
      context.contentResolver
        .query(Phone.CONTENT_URI, columns, null, null, "${Phone.IS_SUPER_PRIMARY} DESC")
        ?.use { cursor ->
          while (cursor.moveToNext()) {
            val name = cursor.getString(1) ?: continue
            val number = cursor.getString(2) ?: continue
            contacts.add(CallContact(cursor.getLong(0), name, number))
          }
        }
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not read contacts: %s", e)
    }
    return contacts
  }

  private fun startExpiry() {
    handler.removeCallbacks(expirePending)
    handler.postDelayed(expirePending, PENDING_TIMEOUT_MS)
  }

  /** "9876" becomes "9 8 7 6", so a speech engine reads the digits one by one. */
  private fun spellOut(number: String): String = number.toList().joinToString(" ")

  companion object {
    private const val TAG = "CallVoiceCommands"
    private const val PENDING_TIMEOUT_MS = 20_000L

    /** Lets the speech recognizer finish its last session before a new one starts. */
    private const val LISTEN_AGAIN_DELAY_MS = 400L
  }
}
