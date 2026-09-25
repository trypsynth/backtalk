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

import static com.google.android.accessibility.talkback.analytics.TalkBackAnalytics.TRAINING_SECTION_TUTORIAL_WEAR;

import androidx.annotation.Nullable;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics;
import com.google.android.accessibility.talkback.trainingcommon.PageConfig;
import com.google.android.accessibility.talkback.trainingcommon.TrainingActivityInterfaceInjector.TrainingSectionLogger;
import com.google.android.accessibility.talkback.trainingcommon.TrainingConfig;
import com.google.android.accessibility.talkback.trainingcommon.TrainingMetricStore;
import com.google.common.collect.ImmutableMap;

/**
 * A logger for {@link TalkBackAnalytics.TrainingSectionId} events in an activity session on Wear.
 */
public class TrainingSectionLoggerImpl implements TrainingSectionLogger {

  private static final ImmutableMap<Integer, Integer> PAGE_TO_SESSION_ID =
      ImmutableMap.<Integer, Integer>builder()
          .put(R.string.welcome_to_talkback_title, TRAINING_SECTION_TUTORIAL_WEAR)
          .buildOrThrow();

  private final TrainingMetricStore trainingMetricStore;
  private boolean trainingLogged;

  public TrainingSectionLoggerImpl(TrainingMetricStore trainingMetricStore) {
    this.trainingMetricStore = trainingMetricStore;
    trainingLogged = false;
  }

  @Override
  public void logEnterSection(TrainingConfig config, @Nullable PageConfig pageConfig) {
    if (trainingLogged || pageConfig == null) {
      return;
    }

    int page = pageConfig.getPageNameResId();
    if (!PAGE_TO_SESSION_ID.containsKey(page)) {
      return;
    }

    trainingLogged = true;
    if (trainingMetricStore != null) {
      trainingMetricStore.onTutorialEntered(PAGE_TO_SESSION_ID.get(page));
    }
  }
}
