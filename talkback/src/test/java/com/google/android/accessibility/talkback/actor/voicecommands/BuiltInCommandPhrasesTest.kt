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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the English built-in voice commands against each other. One wrong letter is allowed in a
 * long phrase, so no two phrases may be close enough for one to be taken for the other.
 */
class BuiltInCommandPhrasesTest {

  private val phrases: List<Pair<String, String>> by lazy {
    val file = File("src/main/res/values/strings_voicecommands.xml")
    assertTrue("Cannot find ${file.absolutePath}", file.exists())
    Regex("<string name=\"(voice_commands_[a-z0-9_]+)\"[^>]*>([^<]*)</string>")
      .findAll(file.readText())
      .map { it.groupValues[1] to it.groupValues[2].trim().lowercase() }
      .filter { (name, text) ->
        !name.startsWith("voice_commands_help_") && '%' !in text && '[' !in text && '\\' !in text
      }
      .toList()
  }

  /** "language" and "languages" are listed together for one action, so they may be this close. */
  private fun isSingularAndPlural(a: String, b: String) = a + "s" == b || b + "s" == a

  @Test
  fun readsTheCommandPhrases() {
    assertTrue("Expected many phrases, found ${phrases.size}", phrases.size > 40)
  }

  @Test
  fun noTwoLongPhrasesAreOneLetterApart() {
    val clashes =
      phrases
        .flatMapIndexed { i, a -> phrases.drop(i + 1).map { b -> a to b } }
        .filter { (a, b) ->
          a.second != b.second &&
            !isSingularAndPlural(a.first, b.first) &&
            a.second.length >= VoiceCommandFuzzyMatcher.MIN_LENGTH_FOR_SLIP &&
            VoiceCommandFuzzyMatcher.sameCommand(a.second, b.second)
        }
        .map { (a, b) -> "${a.first} \"${a.second}\" and ${b.first} \"${b.second}\"" }
    assertEquals("Phrases too close to each other: $clashes", emptyList<String>(), clashes)
  }
}
