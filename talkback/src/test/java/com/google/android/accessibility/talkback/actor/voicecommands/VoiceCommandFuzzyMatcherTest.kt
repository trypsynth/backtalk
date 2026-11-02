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

package com.google.android.accessibility.talkback.actor.voicecommands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandFuzzyMatcherTest {

  @Test
  fun normalizeStripsPunctuationAndLowercases() {
    val input = "  Open, WhatsApp?!  "
    assertEquals("open whatsapp", VoiceCommandFuzzyMatcher.normalize(input))
  }

  @Test
  fun normalizeStripsPolitePrefixes() {
    assertEquals("go home", VoiceCommandFuzzyMatcher.normalize("please go home"))
    assertEquals("open chrome", VoiceCommandFuzzyMatcher.normalize("hey backtalk open chrome"))
    assertEquals("open settings", VoiceCommandFuzzyMatcher.normalize("backtalk open settings"))
    assertEquals("turn off dnd", VoiceCommandFuzzyMatcher.normalize("could you please turn off dnd"))
  }

  @Test
  fun normalizeSpeechQuirks() {
    assertEquals("turn off dnd", VoiceCommandFuzzyMatcher.normalize("turn of dnd"))
    assertEquals("turn on dnd", VoiceCommandFuzzyMatcher.normalize("turn on d and d"))
  }

  @Test
  fun matchesPhraseExactAndFuzzy() {
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("backtalk settings", "backtalk settings"))
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("open backtalk settings", "backtalk settings"))
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("please open backtalk settings", "open backtalk settings"))
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("turn on the hotspot", "turn on hotspot"))
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("turn of dnd", "turn off dnd"))
    assertTrue(VoiceCommandFuzzyMatcher.matchesPhrase("read from the cursor", "read from cursor"))
    assertFalse(VoiceCommandFuzzyMatcher.matchesPhrase("open settings", "backtalk settings"))
    assertFalse(VoiceCommandFuzzyMatcher.matchesPhrase("open settings", "open backtalk settings"))
    assertFalse(VoiceCommandFuzzyMatcher.matchesPhrase("settings", "backtalk settings"))
    assertFalse(VoiceCommandFuzzyMatcher.matchesPhrase("settings", "open backtalk settings"))
  }

  @Test
  fun matchesAnyTargets() {
    val targets = listOf("turn on hotspot", "enable hotspot", "hotspot on")
    assertTrue(VoiceCommandFuzzyMatcher.matchesAny("turn on hotspot", targets))
    assertTrue(VoiceCommandFuzzyMatcher.matchesAny("please enable hotspot", targets))
    assertTrue(VoiceCommandFuzzyMatcher.matchesAny("hotspot on", targets))
    assertFalse(VoiceCommandFuzzyMatcher.matchesAny("turn off bluetooth", targets))
  }

  @Test
  fun extractPrefixRemainder() {
    val prefixes = listOf("open", "launch", "start", "go to")
    assertEquals("youtube", VoiceCommandFuzzyMatcher.extractPrefixRemainder("open youtube", prefixes))
    assertEquals("google chrome", VoiceCommandFuzzyMatcher.extractPrefixRemainder("please launch google chrome", prefixes))
    assertEquals("settings", VoiceCommandFuzzyMatcher.extractPrefixRemainder("start settings", prefixes))
    assertNull(VoiceCommandFuzzyMatcher.extractPrefixRemainder("turn on wifi", prefixes))
  }

  @Test
  fun levenshteinDistanceAndSimilarity() {
    assertEquals(0, VoiceCommandFuzzyMatcher.levenshteinDistance("test", "test"))
    assertEquals(1, VoiceCommandFuzzyMatcher.levenshteinDistance("test", "tent"))
    assertTrue(VoiceCommandFuzzyMatcher.similarity("whatsapp", "whatsap") > 0.85)
    assertTrue(VoiceCommandFuzzyMatcher.similarity("youtube", "youtub") > 0.85)
  }

  @Test
  fun screenClickAndFindPrefixes() {
    val clickPrefixes = listOf("click on the", "click the", "click on", "click", "tap on", "tap", "press on", "press", "activate", "select")
    val findPrefixes = listOf("find the", "find", "search for the", "search for", "search", "locate", "focus on", "focus")

    assertEquals("play button", VoiceCommandFuzzyMatcher.extractPrefixRemainder("click play button", clickPrefixes))
    assertEquals("play", VoiceCommandFuzzyMatcher.extractPrefixRemainder("click on the play", clickPrefixes))
    assertEquals("submit", VoiceCommandFuzzyMatcher.extractPrefixRemainder("tap submit", clickPrefixes))
    assertEquals("settings", VoiceCommandFuzzyMatcher.extractPrefixRemainder("activate settings", clickPrefixes))

    assertEquals("play button", VoiceCommandFuzzyMatcher.extractPrefixRemainder("find play button", findPrefixes))
    assertEquals("search input", VoiceCommandFuzzyMatcher.extractPrefixRemainder("search for search input", findPrefixes))
    assertEquals("cancel", VoiceCommandFuzzyMatcher.extractPrefixRemainder("locate cancel", findPrefixes))
    assertEquals("next", VoiceCommandFuzzyMatcher.extractPrefixRemainder("focus on next", findPrefixes))
  }
}
