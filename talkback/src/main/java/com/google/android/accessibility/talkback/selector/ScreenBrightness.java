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

package com.google.android.accessibility.talkback.selector;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * Reads and changes the system screen brightness setting. Percentages use the same perceptual
 * scale as the system brightness slider.
 */
final class ScreenBrightness {
  private static final int MIN_SETTING = 1;
  private static final int MAX_SETTING = 255;
  private static final int MAX_PERCENT = 100;
  private static final int PERCENT_STEP = 10;

  // Hybrid log-gamma curve from SettingsLib BrightnessUtils, which the system slider uses.
  private static final float HLG_R = 0.5f;
  private static final float HLG_A = 0.17883277f;
  private static final float HLG_B = 0.28466892f;
  private static final float HLG_C = 0.55991073f;
  private static final float HLG_SCALE = 12f;

  private ScreenBrightness() {}

  static boolean canWrite(Context context) {
    return Settings.System.canWrite(context);
  }

  static void requestWritePermission(Context context) {
    Intent intent =
        new Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:" + context.getPackageName()));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }

  static boolean isAdaptive(Context context) {
    return Settings.System.getInt(
            context.getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
  }

  static int getPercent(Context context) {
    return settingToPercent(getSetting(context));
  }

  /**
   * Moves brightness about one step. The dim end of the curve is coarse, so this picks the setting
   * closest to the next step. Returns false if brightness is already at the limit.
   */
  static boolean adjust(Context context, boolean increase) {
    int oldSetting = getSetting(context);
    if (oldSetting == (increase ? MAX_SETTING : MIN_SETTING)) {
      return false;
    }
    int direction = increase ? 1 : -1;
    int targetPercent =
        Math.round((float) settingToPercent(oldSetting) / PERCENT_STEP) * PERCENT_STEP
            + direction * PERCENT_STEP;
    int newSetting = oldSetting + direction;
    for (int setting = newSetting;
        setting >= MIN_SETTING && setting <= MAX_SETTING;
        setting += direction) {
      if (Math.abs(settingToPercent(setting) - targetPercent)
          <= Math.abs(settingToPercent(newSetting) - targetPercent)) {
        newSetting = setting;
      }
    }
    return Settings.System.putInt(
        context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, newSetting);
  }

  private static int getSetting(Context context) {
    return Settings.System.getInt(
        context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, MAX_SETTING);
  }

  private static int settingToPercent(int setting) {
    float scaled = HLG_SCALE * (setting - MIN_SETTING) / (MAX_SETTING - MIN_SETTING);
    float gamma =
        scaled <= 1
            ? (float) Math.sqrt(scaled) * HLG_R
            : HLG_A * (float) Math.log(scaled - HLG_B) + HLG_C;
    return Math.round(gamma * MAX_PERCENT);
  }
}
