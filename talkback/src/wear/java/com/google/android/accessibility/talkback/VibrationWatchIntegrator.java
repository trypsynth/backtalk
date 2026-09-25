/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.accessibility.talkback;

import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP;
import static com.google.android.accessibility.utils.SettingsUtils.VIBRATION_WATCH_ENABLED;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import com.google.android.accessibility.talkback.gesture.GestureController;
import com.google.android.accessibility.utils.SettingsUtils;

/** Handles the changes when the vibration-watch watch is enabled. */
public class VibrationWatchIntegrator {

  private final Context context;
  private final ContentObserver contentObserver;
  private final GestureController gestureController;

  public VibrationWatchIntegrator(Context context, GestureController gestureController) {
    this.context = context;
    this.gestureController = gestureController;
    contentObserver =
        new ContentObserver(/* handler= */ null) {
          @Override
          public void onChange(boolean selfChange) {
            disableVibrationWatchGesturesIfNeeded();
          }
        };
    context
        .getContentResolver()
        .registerContentObserver(
            Settings.Global.getUriFor(VIBRATION_WATCH_ENABLED),
            /* notifyForDescendants= */ false,
            contentObserver);
    disableVibrationWatchGesturesIfNeeded();
  }

  private void disableVibrationWatchGesturesIfNeeded() {
    boolean vibrationWatchEnabled = SettingsUtils.isVibrationWatchEnabled(context);
    if (vibrationWatchEnabled) {
      gestureController.disableGestures(GESTURE_2_FINGER_DOUBLE_TAP, GESTURE_2_FINGER_SINGLE_TAP);
    } else {
      gestureController.enabledGestures(GESTURE_2_FINGER_DOUBLE_TAP, GESTURE_2_FINGER_SINGLE_TAP);
    }
  }

  public void onShutDown() {
    context.getContentResolver().unregisterContentObserver(contentObserver);
  }
}
