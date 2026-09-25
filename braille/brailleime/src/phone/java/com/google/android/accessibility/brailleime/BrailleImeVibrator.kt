/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.brailleime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
import android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
import android.os.VibrationEffect.Composition.PRIMITIVE_TICK
import android.os.Vibrator
import androidx.annotation.RequiresApi

/** Singleton class which presents vibrations in braille keyboard. */
class BrailleImeVibrator private constructor(context: Context) {

  /**
   * Vibration type in braille keyboard. Types play their steps as a composition when the device
   * supports all of the primitives, and fall back to a one-shot vibration otherwise.
   */
  enum class VibrationType(
    internal val duration: Long,
    internal val amplitude: Int,
    internal vararg val steps: Step,
  ) {
    BRAILLE_COMMISSION(25, 120, Step(PRIMITIVE_TICK)),
    SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY(70, 150, Step(PRIMITIVE_CLICK)),
    NEWLINE_OR_DELETE_WORD(120, 180, Step(PRIMITIVE_CLICK), Step(PRIMITIVE_CLICK, delayMs = 60)),
    HOLD(25, 200, Step(PRIMITIVE_LOW_TICK)),
    OTHER_GESTURES(190, 210, Step(PRIMITIVE_QUICK_RISE)),
    NOTHING_TO_DELETE(150, 110, Step(PRIMITIVE_QUICK_FALL, scale = 0.6f)),
  }

  /** One primitive in a composition, with its scale and the delay before it plays. */
  internal data class Step(val primitive: Int, val scale: Float = 1f, val delayMs: Int = 0)

  private val vibrator = context.getSystemService(Vibrator::class.java)
  private var enabled = false

  fun enable() {
    enabled = true
  }

  fun disable() {
    enabled = false
  }

  /** Vibrates with [Vibrator]. */
  fun vibrate(vibrationType: VibrationType) {
    if (!enabled) {
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isCompositionSupported(vibrationType)) {
      val composition = VibrationEffect.startComposition()
      vibrationType.steps.forEach { composition.addPrimitive(it.primitive, it.scale, it.delayMs) }
      vibrator.vibrate(composition.compose())
      return
    }
    vibrator.vibrate(
      VibrationEffect.createOneShot(vibrationType.duration, vibrationType.amplitude)
    )
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun isCompositionSupported(vibrationType: VibrationType): Boolean =
    vibrator.areAllPrimitivesSupported(*vibrationType.steps.map { it.primitive }.toIntArray())

  companion object {
    private var instance: BrailleImeVibrator? = null

    @JvmStatic
    fun getInstance(context: Context): BrailleImeVibrator =
      instance ?: BrailleImeVibrator(context.applicationContext).also { instance = it }
  }
}
