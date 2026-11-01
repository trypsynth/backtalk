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

  /** Bumped by every new call command, so a slow contact lookup can tell it is out of date. */
  private var lookupId = 0
  private val expirePending = Runnable { pending = null }

  fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
    this.pipeline = pipeline
  }

  /** Returns true when [normalized] was a call request, or the answer to one. */
  fun handleCommand(normalized: String, isPartialResult: Boolean): Boolean {
    val waiting = pending
    // Only a final result counts: a partial "call mo" must not dial, or read a half-heard "yes".
    // While a question is open its answer is the only thing we want, so partials are swallowed.
    if (isPartialResult) return waiting != null

    if (waiting != null) {
      // Anything that is not an answer is an ordinary command, so it falls through.
      return answer(waiting, normalized)
    }

    val parsed = CallCommandParser.parse(normalized) ?: return false
    lookupId++ // A command that follows makes any contact lookup still running out of date.
    when (val command = parsed) {
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
    val id = lookupId
    Thread {
        val contacts = readContacts()
        handler.post {
          if (id != lookupId) return@post // The user has moved on.
          if (contacts == null) {
            speak(context.getString(R.string.voice_commands_call_failed), false)
          } else {
            onContactsMatched(command, CallContactMatcher.match(command.target, contacts))
          }
        }
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
        speak(
          context.getString(
            R.string.voice_commands_call_choose, matches.size, command.target, list),
          true)
      }
    }
  }

  /** Returns true when [normalized] answered the question; false leaves it to other commands. */
  private fun answer(waiting: Pending, normalized: String): Boolean {
    handler.removeCallbacks(expirePending)
    pending = null
    when (waiting) {
      is Pending.Confirm -> {
        when {
          CallCommandParser.isYes(normalized) -> place(waiting)
          CallCommandParser.isNo(normalized) ->
            speak(context.getString(R.string.voice_commands_call_cancelled), false)
          else -> return false
        }
      }
      is Pending.Choose -> {
        val choice = CallCommandParser.parseChoice(normalized, waiting.options.size)
        when {
          choice != null -> {
            val contact = waiting.options[choice - 1]
            confirm(Pending.Confirm(contact.name, contact.number, waiting.speaker))
          }
          CallCommandParser.isNo(normalized) ->
            speak(context.getString(R.string.voice_commands_call_cancelled), false)
          else -> return false
        }
      }
    }
    return true
  }

  private fun confirm(request: Pending.Confirm) {
    pending = request
    speak(
      context.getString(
        if (request.speaker) R.string.voice_commands_call_confirm_speaker
        else R.string.voice_commands_call_confirm,
        request.label),
      true)
  }

  private fun place(request: Pending.Confirm) {
    val telecom = context.getSystemService(TelecomManager::class.java)
    if (telecom == null) {
      speak(context.getString(R.string.voice_commands_call_failed), false)
      return
    }
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
    } catch (e: IllegalStateException) {
      LogUtils.e(TAG, "Could not place call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    }
  }

  // --- Incoming and ongoing calls ---

  private fun answerCall() {
    if (!requirePermissions(listOf(Manifest.permission.ANSWER_PHONE_CALLS))) return
    val telecom = context.getSystemService(TelecomManager::class.java)
    if (telecom == null) {
      speak(context.getString(R.string.voice_commands_call_failed), false)
      return
    }
    try {
      // Android does not say whether a call was ringing, so this can only report that we tried.
      telecom.acceptRingingCall()
      speak(context.getString(R.string.voice_commands_call_answering), false)
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not answer call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    } catch (e: IllegalStateException) {
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
    if (telecom == null) {
      speak(context.getString(R.string.voice_commands_call_failed), false)
      return
    }
    try {
      @Suppress("DEPRECATION") val ended = telecom.endCall()
      speak(
        context.getString(if (ended) announcement else R.string.voice_commands_call_no_call), false)
    } catch (e: SecurityException) {
      LogUtils.e(TAG, "Could not end call: %s", e)
      speak(context.getString(R.string.voice_commands_call_failed), false)
    } catch (e: IllegalStateException) {
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
    val returner = pipeline
    if (returner == null) {
      if (thenListen) startExpiry(PENDING_TIMEOUT_MS)
      return
    }
    val speakOptions =
      SpeakOptions.create().apply {
        setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
        if (thenListen) {
          setCompletedAction {
            // The answer window opens now that the question has been read out.
            startExpiry(PENDING_TIMEOUT_MS)
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
    // Until the speech ends, hold the question open for however long it takes to read.
    if (thenListen) startExpiry(SPEECH_TIMEOUT_MS)
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

  /** Every phone number in the address book, each person's preferred number first, or null. */
  private fun readContacts(): List<CallContact>? {
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
    } catch (e: RuntimeException) {
      LogUtils.e(TAG, "Could not read contacts: %s", e)
      return null
    }
    return contacts
  }

  private fun startExpiry(timeoutMs: Long) {
    handler.removeCallbacks(expirePending)
    handler.postDelayed(expirePending, timeoutMs)
  }

  /** "9876" becomes "9 8 7 6", so a speech engine reads the digits one by one. */
  private fun spellOut(number: String): String = number.toList().joinToString(" ")

  companion object {
    private const val TAG = "CallVoiceCommands"
    /** How long the user has to answer once the question has been read out. */
    private const val PENDING_TIMEOUT_MS = 20_000L

    /** Upper bound for reading the question out, before the answer window starts. */
    private const val SPEECH_TIMEOUT_MS = 60_000L

    /** Lets the speech recognizer finish its last session before a new one starts. */
    private const val LISTEN_AGAIN_DELAY_MS = 400L
  }
}
