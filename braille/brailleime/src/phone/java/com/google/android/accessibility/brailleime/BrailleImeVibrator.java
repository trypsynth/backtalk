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

package com.google.android.accessibility.brailleime;

import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_FALL;
import static android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;

import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.VibrationEffect.Composition;
import android.os.Vibrator;
import androidx.annotation.RequiresApi;

/** Singleton class which presents vibrations in braille keyboard. */
public class BrailleImeVibrator {

  /**
   * Vibration type in braille keyboard. Types play their steps as a composition when the device
   * supports all of the primitives, and fall back to a one-shot vibration otherwise.
   */
  public enum VibrationType {
    BRAILLE_COMMISSION(25, 120, step(PRIMITIVE_TICK, 1f, 0)),
    SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY(70, 150, step(PRIMITIVE_CLICK, 1f, 0)),
    NEWLINE_OR_DELETE_WORD(
        120, 180, step(PRIMITIVE_CLICK, 1f, 0), step(PRIMITIVE_CLICK, 1f, 60)),
    HOLD(25, 200, step(PRIMITIVE_LOW_TICK, 1f, 0)),
    OTHER_GESTURES(190, 210, step(PRIMITIVE_QUICK_RISE, 1f, 0)),
    NOTHING_TO_DELETE(150, 110, step(PRIMITIVE_QUICK_FALL, 0.6f, 0));

    private final int duration;
    private final int amplitude;
    private final Step[] steps;

    VibrationType(int duration, int amplitude, Step... steps) {
      this.duration = duration;
      this.amplitude = amplitude;
      this.steps = steps;
    }
  }

  /** One primitive in a composition, with its scale and the delay before it plays. */
  private static final class Step {
    private final int primitive;
    private final float scale;
    private final int delayMs;

    private Step(int primitive, float scale, int delayMs) {
      this.primitive = primitive;
      this.scale = scale;
      this.delayMs = delayMs;
    }
  }

  private static Step step(int primitive, float scale, int delayMs) {
    return new Step(primitive, scale, delayMs);
  }

  private static BrailleImeVibrator instance;
  private final Vibrator vibrator;
  private boolean enabled = false;

  public static BrailleImeVibrator getInstance(Context context) {
    if (instance == null) {
      instance = new BrailleImeVibrator(context.getApplicationContext());
    }
    return instance;
  }

  private BrailleImeVibrator(Context context) {
    vibrator = (Vibrator) context.getSystemService(Service.VIBRATOR_SERVICE);
  }

  public void enable() {
    enabled = true;
  }

  public void disable() {
    enabled = false;
  }

  /**
   * Vibrates with {@link Vibrator}.
   *
   * @param vibrationType specific vibration type.
   */
  public void vibrate(VibrationType vibrationType) {
    if (!enabled) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isCompositionSupported(vibrationType)) {
      Composition composition = VibrationEffect.startComposition();
      for (Step step : vibrationType.steps) {
        composition.addPrimitive(step.primitive, step.scale, step.delayMs);
      }
      vibrator.vibrate(composition.compose());
      return;
    }
    vibrator.vibrate(
        VibrationEffect.createOneShot(vibrationType.duration, vibrationType.amplitude));
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private boolean isCompositionSupported(VibrationType vibrationType) {
    int[] primitives = new int[vibrationType.steps.length];
    for (int i = 0; i < primitives.length; i++) {
      primitives[i] = vibrationType.steps[i].primitive;
    }
    return vibrator.areAllPrimitivesSupported(primitives);
  }
}
