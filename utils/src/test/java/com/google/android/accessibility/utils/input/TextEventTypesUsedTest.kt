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

package com.google.android.accessibility.utils.input

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.monitor.InputModeTracker
import com.google.android.accessibility.utils.output.ActorStateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The text interpreter skips only events that nothing in it uses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextEventTypesUsedTest {
  private val sent = mutableListOf<Int>()
  private lateinit var interpreter: TextEventInterpreter
  private lateinit var tracker: TextCursorTracker

  @Before
  fun setUp() {
    val app = RuntimeEnvironment.getApplication()
    val history = TextEventHistory()
    tracker = TextCursorTracker()
    interpreter =
      TextEventInterpreter(
        app,
        tracker,
        InputModeTracker(),
        history,
        ActorStateProvider(),
        /* voiceActionDelegate= */ null,
        /* voiceDictationDelegate= */ null,
        TextEventFilter(app, tracker, history),
        /* cacheAndDropFrequentEvents= */ false,
        /* readLineWhenMoveToAdjacentLineByUpDownKey= */ false,
      )
    interpreter.addListener { sent.add(it.event.eventType) }
  }

  @Test
  fun theCursorTrackersEventsAreUsed() {
    val types = tracker.eventTypes
    assertEquals(types, types and TextEventInterpreter.EVENT_TYPES_USED)
  }

  @Test
  fun aHoverExitIsNotUsed() {
    assertEquals(0, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT and TextEventInterpreter.EVENT_TYPES_USED)
  }

  @Test
  fun aTypedLetterIsStillSent() {
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    event.beforeText = ""
    event.text.add("a")
    event.fromIndex = 0
    event.addedCount = 1
    event.removedCount = 0
    event.itemCount = 1

    interpreter.interpret(event, null)

    assertTrue(sent.contains(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))
  }
}
