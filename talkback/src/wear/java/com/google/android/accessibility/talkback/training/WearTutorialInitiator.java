/*
 * Copyright (C) 2023 Google Inc.
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

import static com.google.android.accessibility.talkback.trainingcommon.NavigationButtonBar.BUTTON_TYPE_EXIT;
import static com.google.android.accessibility.talkback.trainingcommon.NavigationButtonBar.BUTTON_TYPE_NEXT;
import static com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageAndContentPredicate.SUPPORT_EXIT_BANNER;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import androidx.annotation.VisibleForTesting;
import androidx.wear.widget.ConfirmationOverlay;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.compose.widget.ComposeDialogKt;
import com.google.android.accessibility.talkback.flags.Flags;
import com.google.android.accessibility.talkback.trainingcommon.PageConfig;
import com.google.android.accessibility.talkback.trainingcommon.PageConfig.PageId;
import com.google.android.accessibility.talkback.trainingcommon.TrainingActivity;
import com.google.android.accessibility.talkback.trainingcommon.TrainingConfig;
import com.google.android.accessibility.talkback.trainingcommon.TrainingSwipeDismissListener;
import com.google.common.collect.ImmutableList;
import java.time.Duration;

/** Sets up the TalkBack tutorial content on Wear. */
public final class WearTutorialInitiator {

  private static final Duration WEAR_DEFAULT_ANNOUNCEMENT_INITIAL_DELAY = Duration.ofSeconds(60);
  private static final Duration WEAR_DEFAULT_ANNOUNCEMENT_REPEATED_DELAY = Duration.ofSeconds(30);

  @VisibleForTesting
  static final String WEAR_GBOARD_PACKAGE = "com.google.android.inputmethod.latin";

  private static final String INTENT_ACTION_LAUNCH_KEYBOARD =
      "com.google.android.wearable.action.LAUNCH_KEYBOARD";

  /** A class encapsulating interaction logic with a user for the go back tutorial page. */
  static class GoBackTutorialSwipeDismissListener implements TrainingSwipeDismissListener {

    /** Sets to true if the {@link #onDismissed(TrainingActivity)} has been invoked. */
    boolean invoked = false;

    @Override
    public boolean onDismissed(TrainingActivity activity) {
      if (!invoked) {
        if (Flags.ENABLE_COMPOSE_CONFIRMATION_DIALOG) {
          showComposeConfirmationDialog(activity);
        } else {
          showConfirmationOverlay(activity);
        }
        invoked = true;
        return true;
      }

      return false;
    }

    private void showComposeConfirmationDialog(TrainingActivity activity) {
      View confirmationDialog =
          ComposeDialogKt.createSuccessConfirmationDialog(
              activity,
              activity.getString(R.string.wear_training_go_back_success_confirmation_curved_text),
              activity.getString(
                  R.string.wear_training_go_back_success_confirmation_overlay_message),
              () -> null);
      activity
          .getWindow()
          .addContentView(
              confirmationDialog,
              new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void showConfirmationOverlay(TrainingActivity activity) {
      new ConfirmationOverlay()
          .setType(ConfirmationOverlay.SUCCESS_ANIMATION)
          .setMessage(
              (CharSequence)
                  activity.getString(
                      R.string.wear_training_go_back_success_confirmation_overlay_message))
          .showOn(activity);
    }
  }

  public static final PageConfig.Builder WELCOME_TO_TALKBACK_WATCH_PAGE =
      PageConfig.builder(
              PageId.PAGE_ID_WELCOME_TO_TALKBACK_WATCH, R.string.welcome_to_talkback_title)
          .hidePageNumber()
          .addExitBanner(SUPPORT_EXIT_BANNER)
          .addText(R.string.wear_training_welcome_paragraph)
          .setNavigationButtons(ImmutableList.of(BUTTON_TYPE_NEXT, BUTTON_TYPE_EXIT))
          .setIdleAnnouncement(
              R.string.welcome_to_talkback_page_idle_announcement,
              (int) WEAR_DEFAULT_ANNOUNCEMENT_INITIAL_DELAY.toMillis(),
              (int) WEAR_DEFAULT_ANNOUNCEMENT_REPEATED_DELAY.toMillis());

  public static final PageConfig.Builder VOLUME_UP_WATCH_PAGE =
      PageConfig.builder(PageId.PAGE_ID_WATCH_VOLUME_UP, R.string.wear_training_volume_up_title)
          .hidePageNumber()
          .addText(R.string.wear_training_volume_up_text);

  public static final PageConfig.Builder VOLUME_DOWN_WATCH_PAGE =
      PageConfig.builder(PageId.PAGE_ID_WATCH_VOLUME_DOWN, R.string.wear_training_volume_down_title)
          .hidePageNumber()
          .addText(R.string.wear_training_volume_down_text);

  public static final PageConfig.Builder OPEN_TALKBACK_MENU_WATCH_PAGE =
      PageConfig.builder(
              PageId.PAGE_ID_WATCH_OPEN_TALKBACK_MENU,
              R.string.wear_training_open_talkback_menu_title)
          .hidePageNumber()
          .addText(R.string.wear_training_open_talkback_menu_text);

  public static final PageConfig.Builder typingPage =
      PageConfig.builder(PageId.PAGE_ID_WATCH_TYPING, R.string.wear_training_typing_title)
          .hidePageNumber()
          .addText(R.string.wear_training_typing_text1)
          .addText(R.string.wear_training_typing_text2)
          .addText(R.string.wear_training_typing_text3)
          .addText(R.string.wear_training_typing_text4)
          .addText(R.string.wear_training_typing_text5)
          .addIconButton(
              R.drawable.keyboard,
              R.string.wear_training_typing_practice_button_content_description,
              v -> startKeyboardIntent(v.getContext()));

  public static final PageConfig.Builder END_TUTORIAL_WATCH_PAGE =
      PageConfig.builder(
              PageId.PAGE_ID_WATCH_END_TUTORIAL, R.string.wear_training_end_tutorial_title)
          .hidePageNumber()
          .setNavigationButtons(ImmutableList.of(BUTTON_TYPE_EXIT));

  private WearTutorialInitiator() {}

  private static ImmutableList<PageConfig.Builder> createWearPageConfigList(Context context) {
    ImmutableList.Builder<PageConfig.Builder> builder = ImmutableList.builder();
    builder.add(
        WELCOME_TO_TALKBACK_WATCH_PAGE,
        getWearScrollingPage(context),
        getWearGoBackPage(context)
            .setTrainingSwipeDismissListener(new GoBackTutorialSwipeDismissListener()),
        VOLUME_UP_WATCH_PAGE,
        VOLUME_DOWN_WATCH_PAGE,
        OPEN_TALKBACK_MENU_WATCH_PAGE);
    if (isUsingGboard(context)) {
      builder.add(typingPage);
    }
    builder.add(END_TUTORIAL_WATCH_PAGE);
    return builder.build();
  }

  private static boolean isUsingGboard(Context context) {
    String imeType =
        Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
    return imeType != null && imeType.contains(WEAR_GBOARD_PACKAGE);
  }

  private static void startKeyboardIntent(Context context) {
    ResolveInfo resolveInfo =
        context
            .getPackageManager()
            .resolveActivity(new Intent(INTENT_ACTION_LAUNCH_KEYBOARD), PackageManager.MATCH_ALL);
    if (resolveInfo != null && resolveInfo.activityInfo != null) {
      context.startActivity(new Intent(INTENT_ACTION_LAUNCH_KEYBOARD));
    }
  }

  public static TrainingConfig createTutorialForWatch(Context context) {
    return TrainingConfig.builder(R.string.welcome_to_talkback_title)
        .setPages(createWearPageConfigList(context))
        .setButtons(ImmutableList.of(BUTTON_TYPE_NEXT))
        .build();
  }

  static PageConfig.Builder getWearScrollingPage(Context context) {
    return PageConfig.builder(PageId.PAGE_ID_WATCH_SCROLLING, R.string.talkback_preferences_title)
        .hidePageNumber()
        .addText(context.getString(R.string.wear_training_scroll_text, 2))
        .addList(R.array.tutorial_scrolling_item_titles, R.array.tutorial_scrolling_item_summaries);
  }

  static PageConfig.Builder getWearGoBackPage(Context context) {
    return PageConfig.builder(PageId.PAGE_ID_WATCH_GO_BACK, R.string.wear_training_go_back_title)
        .hidePageNumber()
        .addText(context.getString(R.string.wear_training_go_back_text, 2));
  }
}
