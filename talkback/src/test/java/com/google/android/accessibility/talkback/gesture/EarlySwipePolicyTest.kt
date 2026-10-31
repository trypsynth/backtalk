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

import com.google.android.accessibility.utils.input.CursorGranularity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlySwipePolicyTest {

  private fun isUndoable(action: String?, granularity: CursorGranularity) =
    EarlySwipePolicy.isUndoable(action, "next", "previous", granularity)

  @Test
  fun nextAndPrevious_areUndoable() {
    assertTrue(isUndoable("next", CursorGranularity.DEFAULT))
    assertTrue(isUndoable("previous", CursorGranularity.DEFAULT))
  }

  @Test
  fun otherActions_areNotUndoable() {
    assertFalse(isUndoable("back", CursorGranularity.DEFAULT))
    assertFalse(isUndoable(null, CursorGranularity.DEFAULT))
  }

  @Test
  fun movingTheTextCursor_isNotUndoable() {
    assertFalse(isUndoable("next", CursorGranularity.CHARACTER))
    assertFalse(isUndoable("next", CursorGranularity.WORD))
    assertFalse(isUndoable("next", CursorGranularity.LINE))
  }

  @Test
  fun movingByTableRow_isNotUndoable() {
    assertFalse(isUndoable("next", CursorGranularity.ROW_COLUMN))
  }
}
