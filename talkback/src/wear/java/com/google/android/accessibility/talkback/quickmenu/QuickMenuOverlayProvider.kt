package com.google.android.accessibility.talkback.quickmenu

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.TalkBackUIActor
import com.google.android.accessibility.talkback.flags.Flags

object QuickMenuOverlayProvider {
  @JvmStatic
  fun provideQuickMenuOverlays(
    context: Context,
    typeToOverlay: MutableMap<TalkBackUIActor.Type, QuickMenuOverlay>,
  ) {
    typeToOverlay.apply {
      this[TalkBackUIActor.Type.SELECTOR_MENU_ITEM_OVERLAY_MULTI_FINGER] =
        QuickMenuOverlay(context, R.layout.quick_menu_item_overlay)
      this[TalkBackUIActor.Type.SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER] =
        QuickMenuOverlay(context, R.layout.quick_menu_item_overlay_without_multifinger_gesture)

      if (Flags.ENABLE_COMPOSE_CONFIRMATION_DIALOG) {
        this[TalkBackUIActor.Type.SELECTOR_ITEM_ACTION_OVERLAY] =
          WearComposeQuickMenuOverlay(context)
        this[TalkBackUIActor.Type.GESTURE_OR_KEYBOARD_ACTION_OVERLAY] =
          WearComposeQuickMenuOverlay(context)
      } else {
        this[TalkBackUIActor.Type.SELECTOR_ITEM_ACTION_OVERLAY] =
          QuickMenuOverlay(context, R.layout.quick_menu_item_action_overlay)
        this[TalkBackUIActor.Type.GESTURE_OR_KEYBOARD_ACTION_OVERLAY] =
          QuickMenuOverlay(context, R.layout.quick_menu_item_action_overlay)
      }
    }
  }
}
