package com.google.android.accessibility.talkback.quickmenu

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ConfirmationDialogContent
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.confirmationDialogCurvedText
import com.google.android.accessibility.material.theme.AccessibilitySuiteTheme
import com.google.android.accessibility.talkback.Feedback.TalkBackUI
import com.google.android.accessibility.talkback.R
import kotlinx.coroutines.delay

/**
 * An non-focusable Wear-specific overlay window to show what quick menu item or action is changed.
 * The focus shouldn't be changed while the overlay is showing or hiding.
 */
class WearComposeQuickMenuOverlay(context: Context) :
  QuickMenuOverlay(context, R.layout.quick_menu_item_action_overlay) {

  private var supportedCase: Boolean = false
  private var isVisible = false

  override fun setUI(talkBackUI: TalkBackUI?) {
    supportedCase = talkBackUI != null && talkBackUI.item() in supportedTalkBackUiItem
    super.setUI(talkBackUI)
  }

  override fun show(showIcon: Boolean) {
    val talkBackUI = this.talkBackUi
    if (supportedCase && talkBackUI != null) {
      val composeContentView =
        ComposeView(context).apply {
          setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
          setContent {
            AccessibilitySuiteTheme {
              AppScaffold(timeText = {}) {
                ScreenScaffold {
                  A11yVolumeConfirmationDialog(visible = isVisible, talkBackUI = talkBackUI) {
                    hide()
                  }
                }
              }
            }
          }
        }
      rootView.removeAllViews()
      rootView.addView(
        composeContentView,
        ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        ),
      )
      // We directly call SimpleOverlay#show.
      super.show()
      isVisible = true
    } else {
      super.show(showIcon)
    }
  }

  override fun hide() {
    isVisible = false
    if (supportedCase) {
      rootView.removeAllViews()
    }
    super.hide()
  }

  companion object {
    val supportedTalkBackUiItem =
      setOf(
        TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_DECREASE,
        TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_INCREASE,
        TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MAXIMUM,
        TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MINIMUM,
      )
  }
}

@Composable
fun A11yVolumeConfirmationDialog(
  visible: Boolean,
  talkBackUI: TalkBackUI,
  onDismissListener: () -> Unit,
) {

  val iconResId = talkBackUI.toIcon()
  val curvedTextResId = talkBackUI.toCurvedText()
  val curvedText = stringResource(curvedTextResId)
  val curvedTextStyle = ConfirmationDialogDefaults.curvedTextStyle
  val currentOnDismissListener by rememberUpdatedState(onDismissListener)

  val a11yDurationMillis =
    LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
      originalTimeoutMillis = ConfirmationDialogDefaults.DurationMillis,
      containsIcons = true,
      containsText = true,
      containsControls = false,
    ) ?: ConfirmationDialogDefaults.DurationMillis
  LaunchedEffect(visible, talkBackUI) {
    if (visible) {
      delay(a11yDurationMillis)
      currentOnDismissListener()
    }
  }

  ConfirmationDialogContent(
    curvedText = { confirmationDialogCurvedText(curvedText, curvedTextStyle) },
    colors = ConfirmationDialogDefaults.colors(),
  ) {
    Icon(
      painter = painterResource(iconResId),
      contentDescription = null,
      modifier = Modifier.size(ConfirmationDialogDefaults.IconSize),
    )
  }
}

private fun TalkBackUI.toIcon(): Int {
  return when (item()) {
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_DECREASE -> {
      R.drawable.volume_down_24px
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_INCREASE -> {
      R.drawable.volume_up_24px
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MAXIMUM -> {
      R.drawable.volume_up_24px
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MINIMUM -> {
      R.drawable.volume_mute_24px
    }
    else -> {
      0
    }
  }
}

private fun TalkBackUI.toCurvedText(): Int {
  return when (item()) {
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_DECREASE -> {
      R.string.template_volume_change_volume_down_curved_text
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_INCREASE -> {
      R.string.template_volume_change_volume_up_curved_text
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MAXIMUM -> {
      R.string.template_volume_change_maximum
    }
    TalkBackUI.Item.ITEM_ACCESSIBILITY_VOLUME_MINIMUM -> {
      R.string.template_volume_change_minimum
    }
    else -> {
      0
    }
  }
}
