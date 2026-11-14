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

package com.google.android.accessibility.talkback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlin.math.pow

/**
 * Works out how loud to speak "Backtalk off".
 *
 * Android turns the accessibility volume up to the media volume's step before Backtalk speaks that
 * last announcement. To keep it at the accessibility volume, the speech is made quieter by the
 * difference between the two steps. Android 9 and later report each step's real loudness for the
 * current output, which is exact. The accessibility stream's own loudness is used for both,
 * because the media stream's can differ from it. Before Android 9, the difference is guessed from
 * where the two volume sliders are, which lowers the speech too little at the quietest
 * accessibility volumes, where Android's volume steps are furthest apart.
 */
object FinalAnnouncementVolume {
  /**
   * Returns the speech volume, from 0 to 1, for the last announcement.
   *
   * @param speechVolume the user's speech volume, from 0 to 1
   * @param accessibilityIndex the accessibility stream's volume step, or -1 if unknown
   * @param accessibilityMaxIndex the accessibility stream's highest step, or -1 if unknown
   */
  @JvmStatic
  fun calculate(
    audioManager: AudioManager,
    speechVolume: Float,
    accessibilityIndex: Int,
    accessibilityMaxIndex: Int,
  ): Float {
    val musicIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val musicMaxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (
      musicIndex <= 0 || musicMaxIndex <= 0 || accessibilityIndex < 0 || accessibilityMaxIndex <= 0
    ) {
      // The media volume is muted, or a volume is unknown, so there is nothing to match.
      return speechVolume
    }
    if (accessibilityIndex == 0) {
      return 0f
    }
    val gain =
      reportedGain(audioManager, accessibilityIndex, accessibilityMaxIndex, musicIndex, musicMaxIndex)
        ?: sliderGain(
          accessibilityIndex.toFloat() / accessibilityMaxIndex,
          musicIndex.toFloat() / musicMaxIndex,
        )
    return speechVolume * gain
  }

  /**
   * The accessibility step that Android sets for media step [musicIndex], the way it matches the
   * steps of two streams: in tenths of a step, from each stream's lowest step to its highest, then
   * rounded. Bluetooth headphones can set the media volume between steps, which apps can't see,
   * so there this can be a step off.
   */
  @JvmStatic
  fun accessibilityStepAtMediaVolume(
    musicIndex: Int,
    musicMinIndex: Int,
    musicMaxIndex: Int,
    accessibilityMinIndex: Int,
    accessibilityMaxIndex: Int,
  ): Int {
    val musicRange = (musicMaxIndex - musicMinIndex) * 10
    val accessibilityRange = (accessibilityMaxIndex - accessibilityMinIndex) * 10
    val tenths =
      accessibilityMinIndex * 10 +
        ((musicIndex - musicMinIndex) * 10 * accessibilityRange + musicRange / 2) / musicRange
    return (tenths + 5) / 10
  }

  /**
   * The gain that turns a sound at the media step into one at the accessibility step, from 0 to 1,
   * from the loudness Android reports for each. That is usually in decibels, which are at most
   * 0 at full volume, but Samsung phones report a fraction of full loudness instead, from 0 to 1.
   * Their fractions are above 0 at every step but the lowest, which is never asked for, so two
   * values above 0 are fractions.
   */
  @JvmStatic
  fun gainFromReportedVolumes(accessibility: Float, music: Float): Float {
    if (accessibility > 0f && music > 0f) {
      return (accessibility / music).coerceIn(0f, 1f)
    }
    return gainFromDecibels(accessibility, music)
  }

  /** The gain that turns a sound at [musicDb] into one at [accessibilityDb], from 0 to 1. */
  @JvmStatic
  fun gainFromDecibels(accessibilityDb: Float, musicDb: Float): Float {
    if (accessibilityDb >= musicDb) {
      return 1f
    }
    return 10f.pow((accessibilityDb - musicDb) / 20f).coerceIn(0f, 1f)
  }

  /**
   * TalkBack's guess from the slider positions, from 0 to 1. The parameters came from Google's
   * experiments.
   */
  @JvmStatic
  fun sliderGain(accessibilityFraction: Float, musicFraction: Float): Float {
    if (musicFraction <= accessibilityFraction) {
      return 1f
    }
    return 10f.pow((accessibilityFraction - musicFraction) / 0.4f)
  }

  /**
   * The gain from the accessibility stream's loudness at [accessibilityIndex] and at the step
   * Android sets it to for media step [musicIndex].
   */
  private fun reportedGain(
    audioManager: AudioManager,
    accessibilityIndex: Int,
    accessibilityMaxIndex: Int,
    musicIndex: Int,
    musicMaxIndex: Int,
  ): Float? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return null
    }
    val mediaStep =
      accessibilityStepAtMediaVolume(
        musicIndex,
        audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC),
        musicMaxIndex,
        audioManager.getStreamMinVolume(AudioManager.STREAM_ACCESSIBILITY),
        accessibilityMaxIndex,
      )
    val deviceType = outputDeviceType(audioManager)
    return try {
      gainFromReportedVolumes(
        audioManager.getStreamVolumeDb(
          AudioManager.STREAM_ACCESSIBILITY,
          accessibilityIndex,
          deviceType,
        ),
        audioManager.getStreamVolumeDb(AudioManager.STREAM_ACCESSIBILITY, mediaStep, deviceType),
      )
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  /** The kind of output that media plays on now, such as the speaker or headphones. */
  private fun outputDeviceType(audioManager: AudioManager): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val attributes =
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let {
        return it.type
      }
    }
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return EXTERNAL_OUTPUTS.firstOrNull { type -> outputs.any { it.type == type } }
      ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
  }

  /** Outputs that media goes to instead of the speaker when connected, most likely first. */
  private val EXTERNAL_OUTPUTS =
    listOf(
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_USB_HEADSET,
      AudioDeviceInfo.TYPE_WIRED_HEADSET,
      AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )
}
