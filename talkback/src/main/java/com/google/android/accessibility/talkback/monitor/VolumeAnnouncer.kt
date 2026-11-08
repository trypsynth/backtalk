/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline.FeedbackReturner
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.compat.media.AudioManagerCompatUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import java.util.function.BooleanSupplier

/**
 * Says the new volume as soon as it changes, for [VolumeMonitor], and interrupts other speech,
 * except during continuous reading, which an interruption would stop. TalkBack waited until the
 * keys had been still for 2 seconds, and 2 seconds more after the first press. Each new level
 * replaces the one before it, if that one hasn't finished. Media is said while it plays too, where
 * TalkBack said it only when nothing played.
 */
class VolumeAnnouncer(
  private val context: Context,
  private val pipeline: FeedbackReturner,
  private val callStateMonitor: CallStateMonitor?,
  private val monitor: VolumeMonitor,
) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private val quietBeforeAnnouncement = QuietBeforeAnnouncement(context)

  /** Whether a volume key is held, so that its repeated changes are kept quiet. */
  private var volumeKeyHeld = false

  /** The change held back while a volume key was held, said when the key is released. */
  private var heldChange: HeldChange? = null

  /** The stream that changed last, and when, to keep runs of changes short. */
  private var lastChangedStream = -1
  private var lastChangeTime = 0L

  /** When a volume key last went down, including the repeats of a held key. */
  private var lastVolumeKeyDownTime = -KEY_CHANGE_WINDOW_MS

  /** Where media played at the last media change, to tell when an output brings its own level. */
  private var lastMediaOutput: Set<Pair<Int, Int>>? = null

  /** The media volume at the last media change, to tell a repeat of it from a change. */
  private var lastMediaVolume = -1

  /**
   * Keeps the accessibility volume of the output in use, which Backtalk needs when it turns off.
   * Each output has its own, and Android says when the accessibility stream moves to another one,
   * after the move, where a device callback comes before it.
   */
  private val outputReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val streamType = intent.getIntExtra(AudioManagerCompatUtils.EXTRA_VOLUME_STREAM_TYPE, -1)
        if (streamType == AudioManager.STREAM_ACCESSIBILITY) monitor.cacheAccessibilityStreamVolume()
      }
    }

  /** Whether accessibility focus is on a slider, which says its own value. */
  var sliderFocused = BooleanSupplier { false }

  /** Whether continuous reading is on, which an interruption would stop. */
  var continuousReading = BooleanSupplier { false }

  private val announceAfterHold = Runnable {
    val change = heldChange
    forgetHold()
    if (change != null) announceVolumeNow(change.streamType, change.brief)
  }

  /** Starts following accessibility audio, for announcements made on Bluetooth, and outputs. */
  fun start() {
    lastMediaOutput = mediaOutput()
    lastMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    ContextCompat.registerReceiver(
      context,
      outputReceiver,
      IntentFilter(AudioManagerCompatUtils.STREAM_DEVICES_CHANGED_ACTION),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    quietBeforeAnnouncement.start()
  }

  /** Stops following audio, and drops a held key's volume still to be said. */
  fun stop() {
    context.unregisterReceiver(outputReceiver)
    lastMediaOutput = null
    lastMediaVolume = -1
    forgetHold()
    quietBeforeAnnouncement.stop()
  }

  /**
   * Whether a change of [streamType] to [volume] isn't the user's, so it's ignored: Backtalk's own,
   * or the level of another output, such as headphones that connect. Each output keeps its own
   * media volume, so a media change that comes with a new output and that no volume key made is the
   * output's. Android can report that change before it says the device connected, so the output is
   * looked up when the change comes. Headphones can then report the same level again, which isn't a
   * change either, though a volume key that can't go further is still said.
   */
  fun isIgnoredChange(streamType: Int, volume: Int): Boolean {
    if (quietBeforeAnnouncement.isOwnChange(streamType, volume)) return true
    if (streamType != AudioManager.STREAM_MUSIC) return false
    val output = mediaOutput()
    // Where media played isn't known before start or when Android couldn't say.
    val newOutput = lastMediaOutput != null && output != null && output != lastMediaOutput
    lastMediaOutput = output
    val repeat = volume == lastMediaVolume
    lastMediaVolume = volume
    return (newOutput || repeat) && !changedByVolumeKey()
  }

  /**
   * Says a volume change right away, unless a listener says it ([listenerAnnounces]), a focused
   * slider says it, or a volume key is held. While the key is held, the change is said once the key
   * is released. A change soon after the last one of the same stream says only the percentage.
   */
  fun onVolumeChanged(streamType: Int, listenerAnnounces: Boolean) {
    val byKey = changedByVolumeKey()
    // The key check comes first because finding the focused node asks the app.
    if (listenerAnnounces || (!byKey && sliderFocused.asBoolean)) return
    // Nothing is said, so the next change after the call names the volume again.
    if (isInCall()) return
    val now = SystemClock.uptimeMillis()
    val brief = streamType == lastChangedStream && now - lastChangeTime < SAME_STREAM_WINDOW_MS
    lastChangedStream = streamType
    lastChangeTime = now
    // A held key repeats many times a second, so a missed release doesn't keep later changes quiet.
    if (volumeKeyHeld && byKey) {
      heldChange = HeldChange(streamType, brief)
      return
    }
    announceVolumeNow(streamType, brief)
  }

  /**
   * Follows a volume key that goes to apps. Holding it makes Android repeat the press, which changes
   * the volume several times a second, so those changes are kept quiet and the volume is said once
   * when the key is released.
   */
  fun onVolumeKeyEvent(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_DOWN) {
      lastVolumeKeyDownTime = SystemClock.uptimeMillis()
      if (event.repeatCount == 0) {
        // A new press says its own change, which replaces anything a hold had left to say.
        forgetHold()
      } else {
        volumeKeyHeld = true
      }
    } else if (event.action == KeyEvent.ACTION_UP && volumeKeyHeld) {
      // The volume broadcast for the last repeat can still be on its way, so wait for it.
      handler.removeCallbacks(announceAfterHold)
      handler.postDelayed(announceAfterHold, HOLD_RELEASE_SETTLE_MS)
    }
  }

  private fun forgetHold() {
    handler.removeCallbacks(announceAfterHold)
    volumeKeyHeld = false
    heldChange = null
  }

  private fun changedByVolumeKey(): Boolean =
    SystemClock.uptimeMillis() - lastVolumeKeyDownTime < KEY_CHANGE_WINDOW_MS

  /** @param brief whether to say only the percentage, without the stream name */
  private fun announceVolumeNow(streamType: Int, brief: Boolean) {
    // A newer change replaces an announcement still waiting for quiet.
    quietBeforeAnnouncement.cancel()
    if (!monitor.speakVolumeChanges() || isInCall()) return
    // On Bluetooth, a new media volume is said at the level speech has from now on, after a nudge
    // of the accessibility volume or once earlier speech has stopped. Continuous reading is not
    // stopped for it.
    quietBeforeAnnouncement.announce(
      streamType,
      /* keepSpeech= */ continuousReading.asBoolean,
      {
        pipeline.returnFeedback(
          EVENT_ID_UNTRACKED,
          Feedback.Part.builder().setInterruptAllFeedback(true),
        )
      },
      { speakVolume(streamType, brief) },
    )
  }

  private fun speakVolume(streamType: Int, brief: Boolean) {
    val text =
      if (brief) briefAnnouncement(streamType)
      else monitor.getAnnouncementForStreamType(R.string.template_stream_volume_set, streamType)
    val speakOptions =
      SpeechController.SpeakOptions.create()
        .setQueueMode(
          if (continuousReading.asBoolean) SpeechController.QUEUE_MODE_QUEUE
          else SpeechController.QUEUE_MODE_INTERRUPT
        )
        .setFlags(
          FeedbackItem.FLAG_SOURCE_IS_VOLUME_CONTROL or
            FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE or
            FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP or
            FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP
        )
        .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_VOLUME)
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, speakOptions))
  }

  /**
   * The volume of the stream as only a percentage, for changes that follow one that already named
   * the stream. The ringer still says when it's silent or vibrating.
   */
  private fun briefAnnouncement(streamType: Int): String {
    if (
      streamType == AudioManager.STREAM_RING &&
        audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL
    ) {
      return monitor.getAnnouncementForStreamType(R.string.template_stream_volume_set, streamType)
    }
    return context.getString(
      R.string.tb_template_percent,
      monitor.getStreamVolume(streamType).toString(),
    )
  }

  private fun isInCall(): Boolean = callStateMonitor?.isPhoneCallActive == true

  /** The devices media plays on, by ID and type. A device that reconnects gets a new ID. */
  private fun mediaOutput(): Set<Pair<Int, Int>>? =
    audioManager.outputsFor(MEDIA_ATTRIBUTES)?.map { it.id to it.type }?.toSet()

  private class HeldChange(val streamType: Int, val brief: Boolean)

  private companion object {
    /** How long to wait after a held volume key is released before saying the volume. */
    const val HOLD_RELEASE_SETTLE_MS = 100L

    /** How soon a change has to follow the last one of the same stream to say only the percentage. */
    const val SAME_STREAM_WINDOW_MS = 2000L

    /** How soon a change has to follow a volume key press to count as caused by it. */
    const val KEY_CHANGE_WINDOW_MS = 1000L

    val MEDIA_ATTRIBUTES: AudioAttributes =
      AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
  }
}
