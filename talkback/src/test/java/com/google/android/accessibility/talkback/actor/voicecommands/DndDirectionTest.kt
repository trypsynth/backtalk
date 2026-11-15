/*
 * Copyright (C) 2026 The Android Open Source Project
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


package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DndDirectionTest {

  private fun direction(spoken: String) = dndDirection(VoiceCommandFuzzyMatcher.normalize(spoken))

  @Test
  fun turnsOn() {
    for (phrase in listOf("turn on dnd", "turn on do not disturb", "enable do not disturb", "dnd on", "do not disturb on", "can you turn on d and d")) {
      assertEquals(phrase, true, direction(phrase))
    }
  }

  @Test
  fun turnsOff() {
    for (phrase in listOf("turn off dnd", "turn off do not disturb", "disable do not disturb", "dnd off", "do not disturb off", "turn of dnd", "please turn off do not disturb")) {
      assertEquals(phrase, false, direction(phrase))
    }
  }

  @Test
  fun ignoresOtherCommands() {
    assertNull(direction("battery"))
    assertNull(direction("volume up"))
    assertNull(direction("call mom"))
  }

  @Test
  fun doesNotGuessWhenUnclear() {
    assertNull(direction("turn on dnd and turn off the ringer"))
    assertNull(direction("dnd"))
    assertNull(direction("do not disturb"))
  }
}
