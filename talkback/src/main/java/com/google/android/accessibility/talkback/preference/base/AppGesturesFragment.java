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

import android.content.Context;
import android.os.Bundle;
import androidx.preference.PreferenceScreen;
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.gesture.AppGestures;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The apps that have gestures of their own. */
public class AppGesturesFragment extends TalkbackBaseFragment {
  @Override
  protected CharSequence getTitle() {
    return getText(R.string.title_pref_app_gestures);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
  }

  @Override
  public void onResume() {
    super.onResume();
    listApps();
  }

  private void listApps() {
    Context context = requireContext();
    PreferenceScreen screen = getPreferenceScreen();
    screen.removeAll();
    Map<String, CharSequence> labels = new HashMap<>();
    for (String packageName :
        AppGestures.packagesWithGestures(SharedPreferencesUtils.getSharedPreferences(context))) {
      labels.put(packageName, AppGestures.appLabel(context, packageName));
    }
    if (labels.isEmpty()) {
      AccessibilitySuitePreference none = new AccessibilitySuitePreference(context);
      none.setSummary(R.string.app_gestures_none);
      none.setSelectable(false);
      screen.addPreference(none);
      return;
    }
    List<String> packages = new ArrayList<>(labels.keySet());
    Collator collator = Collator.getInstance();
    packages.sort((a, b) -> collator.compare(labels.get(a).toString(), labels.get(b).toString()));
    for (String packageName : packages) {
      AccessibilitySuitePreference app = new AccessibilitySuitePreference(context);
      app.setTitle(labels.get(packageName));
      app.setSummary(packageName);
      app.setSingleLineTitle(false);
      app.setFragment(AppGestureSettingsFragment.class.getName());
      app.getExtras().putString(AppGestures.ARG_PACKAGE, packageName);
      screen.addPreference(app);
    }
  }
}
