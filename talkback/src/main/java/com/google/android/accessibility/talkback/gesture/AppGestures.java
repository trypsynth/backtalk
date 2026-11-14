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

package com.google.android.accessibility.talkback.gesture;

import static com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_ARGS;
import static com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity.TalkBackSubSettings;
import com.google.android.accessibility.talkback.preference.base.AppGestureSettingsFragment;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SettingsUtils;
import com.google.android.accessibility.utils.monitor.ScreenMonitor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class AppGestures {
  public static final String SAME_AS_OTHER_APPS = "SAME_AS_OTHER_APPS";
  public static final String ARG_PACKAGE = "app_gestures_package";

  private static final String SEPARATOR = "@app:";

  private static volatile @Nullable String focusedPackage;

  private AppGestures() {}

  public static void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
      return;
    }
    CharSequence packageName = event.getPackageName();
    if (packageName != null) {
      focusedPackage = packageName.toString();
    }
  }

  public static @Nullable String focusedPackage() {
    return focusedPackage;
  }

  @VisibleForTesting
  public static void setFocusedPackageForTesting(@Nullable String packageName) {
    focusedPackage = packageName;
  }

  public static String prefKey(String gestureKey, String packageName) {
    return gestureKey + SEPARATOR + packageName;
  }

  static @Nullable String action(SharedPreferences prefs, @Nullable String gestureKey) {
    String packageName = focusedPackage;
    if (packageName == null || gestureKey == null) {
      return null;
    }
    return prefs.getString(prefKey(gestureKey, packageName), null);
  }

  public static Set<String> packagesWithGestures(SharedPreferences prefs) {
    TreeSet<String> packages = new TreeSet<>();
    for (String key : prefs.getAll().keySet()) {
      int separator = key.indexOf(SEPARATOR);
      if (separator > 0) {
        packages.add(key.substring(separator + SEPARATOR.length()));
      }
    }
    return packages;
  }

  public static void removeAll(SharedPreferences prefs, String packageName) {
    String suffix = SEPARATOR + packageName;
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
      if (entry.getKey().endsWith(suffix)) {
        keys.add(entry.getKey());
      }
    }
    SharedPreferences.Editor editor = prefs.edit();
    keys.forEach(editor::remove);
    editor.apply();
  }

  public static CharSequence appLabel(Context context, String packageName) {
    PackageManager packageManager = context.getPackageManager();
    try {
      return packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0));
    } catch (PackageManager.NameNotFoundException e) {
      return packageName;
    }
  }

  // Never for Backtalk itself, so its settings always keep the usual gestures.
  public static boolean canSetGesturesFor(Context context, @Nullable String packageName) {
    return packageName != null
        && !packageName.equals(context.getPackageName())
        && !FormFactorUtils.isAndroidWear()
        && !ScreenMonitor.isDeviceLocked(context)
        && SettingsUtils.allowLinksOutOfSettings(context);
  }

  public static Intent settingsIntent(Context context, String packageName) {
    Bundle args = new Bundle();
    args.putString(ARG_PACKAGE, packageName);
    Intent intent = new Intent(context, TalkBackSubSettings.class);
    intent.putExtra(FRAGMENT_NAME, AppGestureSettingsFragment.class.getName());
    intent.putExtra(FRAGMENT_ARGS, args);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    return intent;
  }
}
