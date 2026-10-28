/*
 * Copyright 2018 Google Inc.
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

package com.google.android.accessibility.talkback.preference.base;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import androidx.fragment.app.FragmentManager;
import android.util.Log;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.TwoStatePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.flags.FeatureFlagReader;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.utils.AccessibilityEventUtils;
import com.google.android.accessibility.utils.AccessibilityMessageFormat;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Fragment holding a set of developer preferences. */
public class DeveloperPrefFragment extends TalkbackBaseFragment {

  private static final String TAG = "DeveloperPrefFragment";
  private String versionInfo;

  /** Preferences managed by this activity. */
  private SharedPreferences prefs;

  private Context context;
  private FragmentManager fragmentManager;

  /** AlertDialog to ask if user really wants to enable node tree debugging. */
  private A11yAlertDialogWrapper treeDebugDialog;

  /** AlertDialog to ask if user really wants to enable node tree debugging and assign a gesture. */
  private A11yAlertDialogWrapper treeDebugGestureDialog;

  /** AlertDialog to ask if user really wants to enable performance statistics. */
  private A11yAlertDialogWrapper performanceStatsDialog;

  /** AlertDialog to ask if user is willing to opt in the debug support. */
  private A11yAlertDialogWrapper logLevelOptInDialog;

  private int logOptInLevel = Log.ERROR;

  public DeveloperPrefFragment() {
    super(R.xml.developer_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_category_developer_settings);
  }

  @Override
  public @Nullable CharSequence getSubTitle() {
    return shouldShowVersionInSubtitle() ? versionInfo : null;
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    context = getContext();
    fragmentManager = getActivity().getSupportFragmentManager();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);

    initVersionInfo();

    // Remove preferences for features that are not supported by device.
    checkTelevision();

    final @Nullable Preference prefVersion =
        findPreference(getString(R.string.pref_developer_version_code_key));
    if (!shouldShowVersionInSubtitle() && (prefVersion != null) && (versionInfo != null)) {
      prefVersion.setSummary(versionInfo);
    } else {
      getPreferenceScreen().removePreference(prefVersion);
    }

    // Initialize preference dialogs.
    final @Nullable TwoStatePreference prefTreeDebug =
        findPreference(getString(R.string.pref_tree_debug_reflect_key));
    if (prefTreeDebug != null) {
      prefTreeDebug.setOnPreferenceChangeListener(treeDebugChangeListener);
      treeDebugDialog =
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
              .setNegativeButton(android.R.string.cancel, null)
              .setOnCancelListener(null)
              .setTitle(R.string.dialog_title_enable_tree_debug)
              .setMessage(R.string.dialog_message_enable_tree_debug)
              .setPositiveButton(
                  android.R.string.ok,
                  (DialogInterface dialog, int which) -> {
                    SharedPreferencesUtils.storeBooleanAsync(
                        prefs, getString(R.string.pref_tree_debug_key), true);
                    prefTreeDebug.setChecked(true);
                    treeDebugGestureDialog.show();
                  })
              .create();

      treeDebugGestureDialog =
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
              .setNegativeButton(android.R.string.cancel, null)
              .setOnCancelListener(null)
              .setTitle(R.string.dialog_title_enable_tree_debug_gesture)
              .setMessage(R.string.dialog_message_enable_tree_debug_gesture)
              .setPositiveButton(
                  android.R.string.ok,
                  (DialogInterface dialog, int which) -> {
                    SharedPreferencesUtils.putStringPref(
                        prefs,
                        getResources(),
                        R.string.pref_shortcut_4finger_3tap_key,
                        getString(R.string.shortcut_value_print_node_tree));
                  })
              .create();
    }

    final TwoStatePreference prefPerformanceStats =
        findPreference(getString(R.string.pref_performance_stats_reflect_key));
    if (prefPerformanceStats != null) {
      prefPerformanceStats.setOnPreferenceChangeListener(performanceStatsChangeListener);
      performanceStatsDialog =
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
              .setNegativeButton(android.R.string.cancel, null)
              .setOnCancelListener(null)
              .setTitle(R.string.dialog_title_enable_performance_stats)
              .setMessage(R.string.dialog_message_enable_performance_stats)
              .setPositiveButton(
                  android.R.string.ok,
                  (DialogInterface dialog, int which) -> {
                    SharedPreferencesUtils.storeBooleanAsync(
                        prefs, getString(R.string.pref_performance_stats_key), true);
                    prefPerformanceStats.setChecked(true);
                  })
              .create();
    }

    final @Nullable ListPreference logLevelPref =
        findPreference(getString(R.string.pref_log_level_key));
    if (logLevelPref != null) {
      logLevelPref.setOnPreferenceChangeListener(logLevelChangeListener);
      logLevelOptInDialog =
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
              .setNegativeButton(android.R.string.cancel, null)
              .setOnCancelListener(null)
              .setTitle(R.string.dialog_title_extend_log_level)
              .setMessage(R.string.dialog_message_extend_log_level)
              .setPositiveButton(
                  R.string.dialog_ok_buggon_extend_log_level,
                  (DialogInterface dialog, int which) -> {
                    SharedPreferencesUtils.putStringPref(
                        prefs,
                        getResources(),
                        R.string.pref_log_level_key,
                        Integer.toString(logOptInLevel));
                    logLevelPref.setValue(Integer.toString(logOptInLevel));
                    LogUtils.setLogLevel(logOptInLevel);
                  })
              .create();
    }

    final @Nullable TwoStatePreference diagnosisModePref =
        findPreference(getString(R.string.pref_diagnosis_mode_key));
    if (diagnosisModePref != null) {

      // Create confirmation-dialog.
      A11yAlertDialogWrapper diagnosisOptInDialog =
          A11yAlertDialogWrapper.materialDialogBuilder(context, fragmentManager)
              .setNegativeButton(android.R.string.cancel, null)
              .setOnCancelListener(null)
              .setTitle(R.string.dialog_title_diagnosis_mode)
              .setMessage(R.string.dialog_message_diagnosis_mode)
              .setPositiveButton(
                  android.R.string.ok,
                  (DialogInterface dialog, int which) -> {
                    SharedPreferencesUtils.storeBooleanAsync(
                        prefs, getString(R.string.pref_diagnosis_mode_key), true);
                    diagnosisModePref.setChecked(true);
                    updateDisplayForDiagnosisModeOn();
                  })
              .create();

      // When diagnosis-mode preference is turned on, show confirmation-dialog.
      diagnosisModePref.setOnPreferenceChangeListener(
          (preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
              diagnosisOptInDialog.show();
              return false;
            } else {
              updateDisplayForDiagnosisModeOff();
              return true;
            }
          });
    }

    final @Nullable TwoStatePreference serviceGestureDetectionPref =
        findPreference(getString(R.string.pref_talkback_gesture_detection_key));
    if (serviceGestureDetectionPref != null) {
      if (FeatureSupport.supportGestureDetection()) {
        serviceGestureDetectionPref.setOnPreferenceChangeListener(
            (preference, newValue) -> {
              // Turning it on or off only takes effect when Backtalk restarts.
              Toast.makeText(
                      getContext(),
                      R.string.toast_pref_talkback_gesture_detection,
                      Toast.LENGTH_LONG)
                  .show();
              return true;
            });
      } else {
        getPreferenceScreen().removePreference(serviceGestureDetectionPref);
      }
    }

    final @Nullable TwoStatePreference multipleGestureSetPref =
        findPreference(getString(R.string.pref_multiple_gesture_set_key));
    if (multipleGestureSetPref != null) {
      if (FeatureSupport.supportMultipleGestureSet()
          && FeatureFlagReader.useMultipleGestureSet(context)) {
        multipleGestureSetPref.setOnPreferenceChangeListener(
            (preference, newValue) -> {
              final SharedPreferences.Editor prefEditor = prefs.edit();
              // Reset gesture set to set 0
              prefEditor
                  .remove(context.getResources().getString(R.string.pref_gesture_set_key))
                  .apply();
              return true;
            });
      } else {
        getPreferenceScreen().removePreference(multipleGestureSetPref);
      }
    }

    updateDisplayForDiagnosisMode();
  }

  private static boolean shouldShowVersionInSubtitle() {
    // Watch does not have action bar.
    // TV does have an action bar but it doesn't support a subtitle.
    return !FeatureSupport.supportSettingsTheme()
        && !FormFactorUtils.isAndroidWear()
        && !FormFactorUtils.isAndroidTv();
  }

  private void updateDisplayForDiagnosisMode() {
    if (PreferencesActivityUtils.isDiagnosisModeOn(prefs, context.getResources())) {
      updateDisplayForDiagnosisModeOn();
    } else {
      updateDisplayForDiagnosisModeOff();
    }
  }

  // Turn on diagnosis-mode sub-preferences.
  private void updateDisplayForDiagnosisModeOn() {
    setEnabled(context, R.string.pref_tts_overlay_key, /* enable= */ false);
    setEnabled(context, R.string.pref_echo_recognized_text_speech_key, /* enable= */ false);
    setEnabled(context, R.string.pref_tree_debug_reflect_key, /* enable= */ false);
    setEnabled(context, R.string.pref_log_overlay_key, /* enable= */ false);
    setEnabled(context, R.string.pref_log_level_key, /* enable= */ false);
  }

  // Turn off diagnosis-mode sub-preferences.
  private void updateDisplayForDiagnosisModeOff() {
    setEnabled(context, R.string.pref_tts_overlay_key, /* enable= */ true);
    setEnabled(context, R.string.pref_echo_recognized_text_speech_key, /* enable= */ true);
    setEnabled(context, R.string.pref_tree_debug_reflect_key, /* enable= */ true);
    setEnabled(context, R.string.pref_log_overlay_key, /* enable= */ true);
    setEnabled(context, R.string.pref_log_level_key, /* enable= */ true);
  }

  private void initVersionInfo() {
    versionInfo = null;

    // Shows TalkBack's abbreviated version number in the action bar.
    try {
      PackageInfo packageInfo =
          context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      if (packageInfo != null) {
        long versionCode =
            FeatureSupport.supportLongVersionCode()
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
        versionInfo =
            getString(
                R.string.talkback_preferences_subtitle,
                packageInfo.versionName + " (" + versionCode + ")");
      }
    } catch (NameNotFoundException e) {
      LogUtils.e(TAG, "Can't find PackageInfo by the package name.");
    }
  }

  /** Checks if the device is Android TV and changes preferences where necessary. */
  private void checkTelevision() {
    if (FormFactorUtils.isAndroidTv()) {
      // Add TV-specific explanation for node tree debugging.
      final Preference treeDebugPreference =
          findPreference(getString(R.string.pref_tree_debug_reflect_key));
      treeDebugPreference.setSummary(getString(R.string.summary_pref_tree_debug_tv));
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    updateDumpA11yEventPreferenceSummary();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Methods

  private void updateDumpA11yEventPreferenceSummary() {
    final Preference prefDumpA11yEvent =
        findPreference(getString(R.string.pref_dump_a11y_event_key));

    if (prefDumpA11yEvent == null || prefs == null) {
      return;
    }

    int count = 0;
    int[] eventTypes = AccessibilityEventUtils.getAllEventTypes();

    int dumpEventMask = prefs.getInt(getString(R.string.pref_dump_event_mask_key), 0);

    for (int eventType : eventTypes) {
      if ((eventType & dumpEventMask) != 0) {
        count++;
      }
    }

    prefDumpA11yEvent.setSummary(
        AccessibilityMessageFormat.formatNamedArgs(
            context, context.getString(R.string.template_dump_event_count), "count", count));
  }

  // TODO: Separate function for duplicate OnPreferenceChangeListener code.
  private final OnPreferenceChangeListener treeDebugChangeListener =
      new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {

          // If the user is trying to turn node tree debugging on, show
          // a confirmation dialog and don't change anything.
          if (Boolean.TRUE.equals(newValue)) {
            treeDebugDialog.show();
            return false;
          }

          // If the user is turning node tree debugging off, then any
          // gestures currently set to print the node tree should be
          // made unassigned.
          disableAndRemoveGesture(
              R.string.pref_tree_debug_key, R.string.shortcut_value_print_node_tree);

          return true;
        }
      };

  // TODO: Separate function for duplicate OnPreferenceChangeListener code.
  private final OnPreferenceChangeListener performanceStatsChangeListener =
      new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
          // If the user is enabling performance statistics... show confirmation dialog.
          if (Boolean.TRUE.equals(newValue)) {
            performanceStatsDialog.show();
            return false;
          }

          // If the user is disabling performance statistics... disable & unassign gesture.
          disableAndRemoveGesture(
              R.string.pref_performance_stats_key, R.string.shortcut_value_print_performance_stats);

          return true;
        }
      };

  private final OnPreferenceChangeListener logLevelChangeListener =
      new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
          // If the user is trying to extend log level, shows a confirmation dialog and don't change
          // anything.
          logOptInLevel = Integer.parseInt((String) newValue);
          if (logOptInLevel < Log.ERROR) {
            logLevelOptInDialog.show();
            return false;
          }

          LogUtils.setLogLevel(logOptInLevel);
          return true;
        }
      };

  protected void disableAndRemoveGesture(int prefKeyRes, int shortcutRes) {
    // Set preference to false
    final SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    final SharedPreferences.Editor prefEditor = prefs.edit();
    prefEditor.putBoolean(getString(prefKeyRes), false);
    // Gestures may need to be reassigned if disabling developer options, like node tree
    // debugging and performance tracking.
    final String[] gesturePrefKeys = getResources().getStringArray(R.array.pref_shortcut_keys);

    // For each gesture that matches shortcut... unassign gesture.
    for (String prefKey : gesturePrefKeys) {
      final String currentValue = prefs.getString(prefKey, null);
      if (getString(shortcutRes).equals(currentValue)) {
        prefEditor.putString(prefKey, getString(R.string.shortcut_value_unassigned));
      }
    }
    prefEditor.apply();
  }
}
