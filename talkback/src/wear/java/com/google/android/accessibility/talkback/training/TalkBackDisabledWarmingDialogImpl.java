/*
 * Copyright (C) 2024 Google Inc.
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

package com.google.android.accessibility.talkback.training;

import androidx.fragment.app.FragmentActivity;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.trainingcommon.TrainingActivityInterfaceInjector.TalkBackDisabledWarmingDialog;
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper;
import java.util.function.Consumer;

/** Provides the warming dialog shown in the tutorial page on Wear when TalkBack is disabled. */
public class TalkBackDisabledWarmingDialogImpl implements TalkBackDisabledWarmingDialog {

  @Override
  public void show(FragmentActivity fragmentActivity, Consumer<Boolean> checkBoxConsumer) {
    A11yAlertDialogWrapper.materialDialogBuilder(
            fragmentActivity, fragmentActivity.getSupportFragmentManager())
        .setTitle(R.string.talkback_inactive_title)
        .setMessage(R.string.talkback_inactive_warning_message)
        .setCancelable(true)
        .setNegativeButton(
            R.string.talkback_inactive_close_tutorial_button,
            (dialog, which) -> fragmentActivity.finish())
        .setPositiveButton(
            R.string.talkback_inactive_warning_positive_button, (dialog, which) -> {})
        .create()
        .show();
  }
}
