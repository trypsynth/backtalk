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
import static android.os.VibrationEffect.Composition.PRIMITIVE_QUICK_RISE;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;

import android.app.Service;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.VibrationEffect.Composition;
import android.os.Vibrator;

/** Singleton class which presents vibrations in braille keyboard. */
public class BrailleImeVibrator {

  /**
   * Vibration type in braille keyboard. Types with primitives play them as a composition when the
   * device supports it, and fall back to a one-shot vibration otherwise.
   */
  public enum VibrationType {
    BRAILLE_COMMISSION(25, 120, PRIMITIVE_TICK),
    SPACE_DELETE_OR_MOVE_CURSOR_OR_GRANULARITY(70, 150, PRIMITIVE_CLICK),
    NEWLINE_OR_DELETE_WORD(120, 180, PRIMITIVE_CLICK, PRIMITIVE_CLICK),
    HOLD(25, 200, PRIMITIVE_LOW_TICK),
    OTHER_GESTURES(190, 210, PRIMITIVE_QUICK_RISE),
    SUBMIT(150, 110),
    NOTHING_TO_DELETE(150, 110);

    private final int duration;
    private final int amplitude;
    private final int[] primitives;

    VibrationType(int duration, int amplitude, int... primitives) {
      this.duration = duration;
      this.amplitude = amplitude;
      this.primitives = primitives;
    }
  }

  private static final int PRIMITIVE_GAP_MS = 60;

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
    int[] primitives = vibrationType.primitives;
    if (primitives.length > 0
        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        && vibrator.areAllPrimitivesSupported(primitives)) {
      Composition composition = VibrationEffect.startComposition();
      for (int i = 0; i < primitives.length; i++) {
        composition.addPrimitive(primitives[i], /* scale= */ 1f, i == 0 ? 0 : PRIMITIVE_GAP_MS);
      }
      vibrator.vibrate(composition.compose());
      return;
    }
    vibrator.vibrate(
        VibrationEffect.createOneShot(vibrationType.duration, vibrationType.amplitude));
  }
}
