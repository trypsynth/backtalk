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

package com.google.android.accessibility.talkback.preference.base;

import static com.google.android.accessibility.talkback.preference.base.GestureListPreference.TYPE_ACTION_ITEM;
import static com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_ARGS;
import static com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreferenceCategory;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.AppGestures;
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping;
import com.google.android.accessibility.talkback.preference.PreferencesActivityUtils;
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity.TalkBackSubSettings;
import com.google.android.accessibility.talkback.preference.base.GestureListPreference.ActionItem;
import com.google.android.accessibility.utils.FeatureSupport;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The gestures of one app, each either set for the app or the same as in the other apps. */
public class AppGestureSettingsFragment extends TalkbackBaseFragment {
  private final Map<Preference, GestureListPreference> gestures = new HashMap<>();
  private SharedPreferences prefs;
  private String packageName;

  public AppGestureSettingsFragment() {
    super(R.xml.gesture_preferences);
  }

  @Override
  protected CharSequence getTitle() {
    return getString(
        R.string.title_app_gestures_screen, AppGestures.appLabel(requireContext(), packageName()));
  }

  private String packageName() {
    if (packageName == null) {
      Bundle args = getArguments();
      packageName = args == null ? "" : args.getString(AppGestures.ARG_PACKAGE, "");
    }
    return packageName;
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    Context context = requireContext();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);

    List<String> passThroughKeys =
        FeatureSupport.isMultiFingerGestureSupported()
            ? Arrays.asList(
                context.getResources().getStringArray(R.array.pref_2finger_pass_through_shortcut_keys))
            : new ArrayList<>();
    PreferenceGroup root = getPreferenceScreen();
    for (int i = root.getPreferenceCount() - 1; i >= 0; i--) {
      Preference preference = root.getPreference(i);
      if (!(preference instanceof AccessibilitySuitePreferenceCategory)
          || getString(R.string.pref_category_fingerprint_touch_shortcuts_key)
              .equals(preference.getKey())) {
        root.removePreference(preference);
        continue;
      }
      replaceGestures((PreferenceGroup) preference, passThroughKeys);
    }

    AccessibilitySuitePreference remove = new AccessibilitySuitePreference(context);
    remove.setTitle(R.string.app_gestures_remove);
    remove.setOrder(-1);
    remove.setOnPreferenceClickListener(
        preference -> {
          AppGestures.removeAll(prefs, packageName());
          PreferencesActivityUtils.announceText(
              getString(R.string.app_gestures_removed), getContext());
          updateSummaries();
          return true;
        });
    root.addPreference(remove);
    collapseCategories();
  }

  private void replaceGestures(PreferenceGroup category, List<String> passThroughKeys) {
    for (int i = category.getPreferenceCount() - 1; i >= 0; i--) {
      Preference preference = category.getPreference(i);
      category.removePreference(preference);
      if (!(preference instanceof GestureListPreference)
          || passThroughKeys.contains(preference.getKey())) {
        continue;
      }
      AccessibilitySuitePreference appGesture = new AccessibilitySuitePreference(getContext());
      appGesture.setTitle(preference.getTitle());
      appGesture.setOrder(preference.getOrder());
      appGesture.setSingleLineTitle(false);
      appGesture.setVisible(preference.isVisible());
      appGesture.setOnPreferenceClickListener(this::chooseAction);
      gestures.put(appGesture, (GestureListPreference) preference);
      category.addPreference(appGesture);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    updateSummaries();
  }

  private void updateSummaries() {
    gestures.forEach((appGesture, gesture) -> appGesture.setSummary(summary(gesture)));
  }

  private String summary(GestureListPreference gesture) {
    @Nullable String appAction = prefs.getString(appKey(gesture), null);
    return appAction != null
        ? GestureShortcutMapping.getActionString(requireContext(), appAction)
        : getString(R.string.app_gestures_same_as_other_apps, otherAppsAction(gesture));
  }

  private String otherAppsAction(GestureListPreference gesture) {
    return GestureShortcutMapping.getActionString(
        requireContext(), prefs.getString(gesture.getKey(), gesture.getDefaultValue()));
  }

  private String appKey(GestureListPreference gesture) {
    return AppGestures.prefKey(gesture.getKey(), packageName());
  }

  private boolean chooseAction(Preference appGesture) {
    GestureListPreference gesture = gestures.get(appGesture);
    if (gesture == null) {
      return false;
    }
    List<ActionItem> items = new ArrayList<>();
    items.add(
        new ActionItem(
            getString(R.string.app_gestures_same_as_other_apps, otherAppsAction(gesture)),
            AppGestures.SAME_AS_OTHER_APPS,
            TYPE_ACTION_ITEM));
    items.addAll(Arrays.asList(gesture.getActionItems()));
    Bundle bundle =
        GesturePreferenceFragmentCompat.createBundleForFragmentArguments(
            appKey(gesture),
            appGesture.getTitle(),
            prefs.getString(appKey(gesture), AppGestures.SAME_AS_OTHER_APPS),
            items.toArray(new ActionItem[0]));
    Intent intent = new Intent(getContext(), TalkBackSubSettings.class);
    intent.putExtra(FRAGMENT_NAME, GesturePreferenceFragmentCompat.class.getCanonicalName());
    intent.putExtra(FRAGMENT_ARGS, bundle);
    startActivity(intent);
    return true;
  }
}
