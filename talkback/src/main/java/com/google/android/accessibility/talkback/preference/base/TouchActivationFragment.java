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

import static com.google.android.accessibility.utils.SettingsUtils.isTouchExplorationEnabled;

import android.content.Context;
import android.os.Bundle;
import androidx.preference.Preference;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.FormFactorUtils;
import com.google.android.accessibility.utils.PreferenceSettingsUtils;

/** Settings for how touches focus and activate items. */
public class TouchActivationFragment extends TalkbackBaseFragment {

  public TouchActivationFragment() {
    super(R.xml.touch_activation_preferences);
  }

  @Override
  public CharSequence getTitle() {
    return getText(R.string.title_pref_touch_activation);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    super.onCreatePreferences(savedInstanceState, rootKey);
    if (!FormFactorUtils.isAndroidWear()) {
      PreferenceSettingsUtils.hidePreference(
          getContext(), getPreferenceScreen(), R.string.pref_reserve_vibration_watch_gestures_key);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    updateSingleTap();
  }

  /**
   * Single tap needs explore by touch, which is set in the developer settings, so it can't be a
   * dependency in the XML. Without Backtalk running, explore by touch is off, so the setting the
   * user asked for counts instead.
   */
  private void updateSingleTap() {
    Context context = getContext();
    Preference singleTap = findPreference(getString(R.string.pref_single_tap_key));
    if (context == null || singleTap == null) {
      return;
    }
    boolean touchExploration =
        !TalkBackService.isServiceActive()
            || isTouchExplorationEnabled(context.getContentResolver());
    singleTap.setEnabled(touchExploration);
  }
}
