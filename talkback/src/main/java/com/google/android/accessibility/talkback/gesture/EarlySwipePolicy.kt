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

package com.google.android.accessibility.talkback.gesture

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.scripting.Scripts
import com.google.android.accessibility.utils.input.CursorGranularity

/** Decides which swipes can be performed before the finger lifts. */
object EarlySwipePolicy {

  /**
   * Whether the gesture does nothing but move focus to the next or previous item, which can be
   * undone by moving focus back.
   */
  @JvmStatic
  fun onlyMovesToAdjacentItem(
    context: Context,
    gestureId: Int,
    gestures: GestureController,
    shortcuts: GestureShortcutMapping,
    granularity: CursorGranularity,
  ): Boolean =
    !gestures.isCapturedOrDisabled(gestureId) &&
      !Scripts.takesGesture(gestureId) &&
      isUndoable(
        shortcuts.getActionKeyFromGestureId(gestureId),
        context.getString(R.string.shortcut_value_next),
        context.getString(R.string.shortcut_value_previous),
        granularity,
      )

  /** Moving by character, word or line also moves the text cursor, which can't be undone. */
  internal fun isUndoable(
    action: String?,
    nextAction: String,
    previousAction: String,
    granularity: CursorGranularity,
  ): Boolean =
    !granularity.isMicroGranularity &&
      granularity != CursorGranularity.ROW_COLUMN &&
      (action == nextAction || action == previousAction)
}
