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

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.accessibility.utils.output.LowLatencyAudio

/**
 * Makes a new media volume on Bluetooth headphones sound as loud as the speech after it.
 *
 * Headphones that control their own volume turn everything down by the media volume, and Android
 * raises accessibility audio to make up for it. It works out that boost again at once when the
 * accessibility volume changes, and a little after accessibility audio starts, but not when more
 * audio joins audio that already plays. So Backtalk sets the accessibility volume one step away and
 * back. While its audio plays, that is enough and it speaks at once. When nothing plays, it first
 * plays [PRIME_MS] of silence, so the boost is in place before speech starts. If the accessibility
 * volume can't be changed, speech is stopped and the announcement waits until nothing of Backtalk's
 * has played for [DRAIN_MS], up to [MAX_WAIT_MS], and then plays the silence.
 */
class QuietBeforeAnnouncement
@JvmOverloads
constructor(
  private val context: Context,
  // Tests set this, because Robolectric has no Bluetooth audio.
  private val onBluetooth: () -> Boolean = { accessibilityOnBluetooth(context) },
) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private var watching = false
  private var playing = false
  // When accessibility audio last stopped, in uptime milliseconds.
  private var stoppedAt = 0L
  private var pending: Runnable? = null
  // When the silence that plays before an announcement ends, in uptime milliseconds.
  private var silenceEnds = 0L
  // The accessibility volumes Backtalk's own nudge is about to report, in order.
  private val ownChanges = ArrayDeque<OwnChange>()
  // The media volume the last nudge was made for. Android reports each media change once for media
  // and once for each stream that shares its volume, and one nudge covers all of them.
  private var nudgedForMedia = -1
  // When that nudge was made, in uptime milliseconds. The same level later on is nudged again,
  // since the boost can have changed in between without an announcement, such as during a call.
  private var nudgedAt = 0L

  private val playbackCallback =
    object : AudioManager.AudioPlaybackCallback() {
      override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
        update(accessibilityAudioPlays(configs))
      }
    }

  private val drained = Runnable { finish() }
  private val timeout = Runnable { finish() }
  private val primed = Runnable { speakNow() }

  /** Starts following accessibility audio, so that audio which just ended is waited out too. */
  fun start() {
    if (watching) return
    watching = true
    audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
    playing = accessibilityAudioPlays(audioManager.activePlaybackConfigurations)
  }

  /** Stops following accessibility audio and drops an announcement still waiting. */
  fun stop() {
    cancel()
    nudgedForMedia = -1
    // The receiver is gone, so the changes still on their way won't be seen.
    ownChanges.clear()
    if (!watching) return
    watching = false
    audioManager.unregisterAudioPlaybackCallback(playbackCallback)
  }

  /**
   * Runs [speak] for a change of [streamType], once Backtalk's audio is quiet if it has to be.
   * [stopAudio] stops what Backtalk is saying, unless [keepSpeech] is set: then [speak] runs at
   * once. Call [cancel] first to drop an announcement still waiting.
   */
  fun announce(streamType: Int, keepSpeech: Boolean, stopAudio: Runnable, speak: Runnable) {
    if (!needsQuiet(streamType)) {
      speak.run()
      return
    }
    if (nudgeAccessibilityVolume(streamType)) {
      // Audio that plays takes the new boost at once, and audio that starts takes it only a little
      // later, so silence goes first. Speech that joins the silence waits for it too.
      if (playing && SystemClock.uptimeMillis() >= silenceEnds) {
        speak.run()
      } else {
        pending = speak
        speakAfterSilence()
      }
      return
    }
    if (keepSpeech) {
      speak.run()
      return
    }
    // Also while draining: speech still queued would otherwise start during the wait.
    stopAudio.run()
    LowLatencyAudio.pauseWhenQuiet()
    pending = speak
    handler.postDelayed(timeout, MAX_WAIT_MS)
    if (!playing) waitOutDrain()
  }

  /**
   * Whether a change of [streamType] to [volume] is one of the two that Backtalk's own nudge makes,
   * which aren't said.
   */
  fun isOwnChange(streamType: Int, volume: Int): Boolean {
    if (streamType != AudioManager.STREAM_ACCESSIBILITY) return false
    // A change Android never reported mustn't hide one the user makes later.
    val now = SystemClock.uptimeMillis()
    ownChanges.removeAll { now - it.madeAt > OWN_CHANGE_MS }
    // Android sometimes reports only the last of two quick changes, so skip any it left out.
    val own = ownChanges.indexOfFirst { it.volume == volume }
    if (own >= 0) {
      repeat(own + 1) { ownChanges.removeFirst() }
      return true
    }
    // Someone else changed it: the rest of the nudge won't be reported in this order.
    ownChanges.clear()
    return false
  }

  /**
   * Sets the accessibility volume one step away and back, which makes Android work out the boost
   * again at once, even while speech plays. Setting it to the volume it has is ignored. Returns
   * false if the volume could not be changed, and then the announcement waits for quiet instead.
   */
  private fun nudgeAccessibilityVolume(streamType: Int): Boolean {
    val media = audioManager.getStreamVolume(streamType)
    val now = SystemClock.uptimeMillis()
    if (media == nudgedForMedia && now - nudgedAt < SAME_CHANGE_MS) return true
    val stream = AudioManager.STREAM_ACCESSIBILITY
    val index = audioManager.getStreamVolume(stream)
    val min =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream)
      else 0
    // Down when it can, so the moment between the two changes is quieter rather than louder.
    val nudged = if (index > min) index - 1 else index + 1
    if (nudged > audioManager.getStreamMaxVolume(stream)) return false
    // Android reports each change later on this thread, so queuing it after the change is in time.
    // Changes from an earlier nudge can still be on their way, so these go after them.
    try {
      audioManager.setStreamVolume(stream, nudged, 0)
      if (audioManager.getStreamVolume(stream) != nudged) {
        // Android ignored it, or someone else changed the volume first.
        return false
      }
      ownChanges.addLast(OwnChange(nudged, now))
      audioManager.setStreamVolume(stream, index, 0)
      ownChanges.addLast(OwnChange(index, now))
      if (audioManager.getStreamVolume(stream) != index) {
        // Leaving it a step down would change the user's volume, so try once more.
        audioManager.setStreamVolume(stream, index, 0)
        Log.w(TAG, "Had to set the accessibility volume back to $index again")
      }
      nudgedForMedia = media
      nudgedAt = now
    } catch (e: SecurityException) {
      Log.w(TAG, "Not allowed to nudge the accessibility volume", e)
      return false
    }
    return true
  }

  /** Drops an announcement still waiting. */
  fun cancel() {
    pending = null
    handler.removeCallbacks(drained)
    handler.removeCallbacks(timeout)
    handler.removeCallbacks(primed)
  }

  private fun update(nowPlaying: Boolean) {
    if (nowPlaying == playing) return
    playing = nowPlaying
    if (playing) {
      handler.removeCallbacks(drained)
    } else {
      stoppedAt = SystemClock.uptimeMillis()
      if (pending != null) waitOutDrain()
    }
  }

  /**
   * Android marks a player stopped as soon as its app stops it, but the audio it already sent keeps
   * playing for about 200 to 300 ms on Bluetooth, and the boost changes only once that has played.
   */
  private fun waitOutDrain() {
    handler.removeCallbacks(drained)
    handler.postAtTime(drained, stoppedAt + DRAIN_MS)
  }

  /**
   * Once quiet, starts accessibility audio with silence: the boost is worked out again a little
   * after audio starts, up to 90 ms on Galaxy Buds, and not when more audio joins it.
   */
  private fun finish() {
    if (pending == null) return
    handler.removeCallbacks(drained)
    handler.removeCallbacks(timeout)
    // Only the timeout comes while audio plays. Audio that never stopped keeps its boost, so silence
    // would change nothing.
    if (playing) speakNow() else speakAfterSilence()
  }

  /** Says [pending] once [PRIME_MS] of silence has played, starting the silence if none plays. */
  private fun speakAfterSilence() {
    val now = SystemClock.uptimeMillis()
    if (now >= silenceEnds) {
      if (!LowLatencyAudio.playSilence(context, SPEECH_ATTRIBUTES, PRIME_MS.toInt())) {
        speakNow()
        return
      }
      silenceEnds = now + PRIME_MS
    }
    handler.removeCallbacks(primed)
    handler.postAtTime(primed, silenceEnds)
  }

  private fun speakNow() {
    val speak = pending ?: return
    cancel()
    speak.run()
  }

  /**
   * Whether the announcement for [streamType] needs a nudge or a wait: the media volume changed and
   * Backtalk's audio goes to Bluetooth. Other volumes don't change the boost, and an accessibility
   * volume change makes Android work it out at once.
   */
  private fun needsQuiet(streamType: Int): Boolean =
    watching && streamType == AudioManager.STREAM_MUSIC && onBluetooth()

  private class OwnChange(val volume: Int, val madeAt: Long)

  private companion object {
    /** How long Android has to report a nudge's change before it's forgotten. */
    const val OWN_CHANGE_MS = 2000L

    /**
     * How long after accessibility audio stops the announcement waits. Speech the engine stopped
     * played on for 200 to 282 ms on Galaxy Buds.
     */
    const val DRAIN_MS = 350L

    /** The longest it waits for speech to stop and then for [DRAIN_MS], before [PRIME_MS]. */
    const val MAX_WAIT_MS = 600L

    /** How long the silence plays before the announcement. */
    const val PRIME_MS = 150L

    /** How close together reports count as the same media change, which one nudge covers. */
    const val SAME_CHANGE_MS = 500L

    /** As Backtalk's speech plays, so the silence goes to its output. */
    val SPEECH_ATTRIBUTES: AudioAttributes =
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Whether accessibility audio plays. Android lists only the players that play to apps such as
     * Backtalk. The engine's speech plays in the engine's own app, so players are told apart by
     * their usage, not their app.
     */
    fun accessibilityAudioPlays(configs: List<AudioPlaybackConfiguration>): Boolean =
      configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY }

    /** Whether Backtalk's audio goes to Bluetooth headphones. */
    @SuppressLint("InlinedApi") // The device types are plain numbers that older Android never uses.
    fun accessibilityOnBluetooth(context: Context): Boolean {
      val audioManager = context.getSystemService(AudioManager::class.java)
      // Not media's output: Backtalk's audio can be sent to the phone speaker instead.
      val devices = audioManager.outputsFor(SPEECH_ATTRIBUTES) ?: return false
      return devices.any { it.type in BLUETOOTH_MEDIA_TYPES }
    }
  }
}

private const val TAG = "BacktalkVolumeQuiet"

/**
 * The devices audio with [attributes] plays on, or null when Android can't say. Before Android 13
 * apps can't ask, so the connected outputs stand in for them.
 */
internal fun AudioManager.outputsFor(attributes: AudioAttributes): List<AudioDeviceInfo>? =
  try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getAudioDevicesForAttributes(attributes)
    } else {
      getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }
  } catch (e: RuntimeException) {
    // Some phones throw here, as AudioDeviceRouter found.
    Log.w(TAG, "Could not find where audio plays", e)
    null
  }

/** Bluetooth devices that play media. Older versions of Android never report the newer types. */
@SuppressLint("InlinedApi")
private val BLUETOOTH_MEDIA_TYPES =
  setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
  )
