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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandPhraseTest {

  @Test
  fun stripFillerRemovesPoliteWordsAtTheEdges() {
    assertEquals("go home", VoiceCommandFuzzyMatcher.stripFiller("please go home"))
    assertEquals("go home", VoiceCommandFuzzyMatcher.stripFiller("can you please go home"))
    assertEquals("go home", VoiceCommandFuzzyMatcher.stripFiller("go home please"))
    assertEquals("go home", VoiceCommandFuzzyMatcher.stripFiller("hey backtalk go home"))
  }

  @Test
  fun stripFillerLeavesTheRestAlone() {
    assertEquals("type Hello, World!", VoiceCommandFuzzyMatcher.stripFiller("please type Hello, World!"))
    assertEquals("please", VoiceCommandFuzzyMatcher.stripFiller("please"))
    assertEquals("backtalk settings", VoiceCommandFuzzyMatcher.stripFiller("backtalk settings"))
  }

  @Test
  fun sameCommandAcceptsTheExactPhraseAndPoliteVersions() {
    assertTrue(VoiceCommandFuzzyMatcher.sameCommand("go home", "go home"))
    assertTrue(VoiceCommandFuzzyMatcher.sameCommand("please go home", "go home"))
    assertTrue(VoiceCommandFuzzyMatcher.sameCommand("can you go home", "go home"))
  }

  @Test
  fun sameCommandAllowsOneSlipOnlyInALongPhrase() {
    assertTrue(VoiceCommandFuzzyMatcher.sameCommand("read from the cursr", "read from the cursor"))
    assertTrue(VoiceCommandFuzzyMatcher.sameCommand("scroll dow", "scroll down"))
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("pack", "back"))
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("hole", "home"))
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("read from the cursr and more", "read from the cursor"))
  }

  @Test
  fun sameCommandIgnoresSentencesThatOnlyContainTheWords() {
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("i want to go back to the home screen", "go home"))
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("do not scroll down yet", "scroll down"))
    assertFalse(VoiceCommandFuzzyMatcher.sameCommand("", "go home"))
  }

  @Test
  fun startsWithCommandSkipsPoliteWordsBeforeIt() {
    assertTrue(VoiceCommandFuzzyMatcher.startsWithCommand("type hello", "type"))
    assertTrue(VoiceCommandFuzzyMatcher.startsWithCommand("please type hello", "type"))
    assertFalse(VoiceCommandFuzzyMatcher.startsWithCommand("hello type", "type"))
  }

  @Test
  fun remainderKeepsWhatWasSaidAfterTheCommand() {
    assertEquals(" Hello, World!", VoiceCommandFuzzyMatcher.remainderAfter("please type Hello, World!", "type"))
    assertEquals(" hello", VoiceCommandFuzzyMatcher.remainderAfter("type hello", "type"))
    assertEquals("", VoiceCommandFuzzyMatcher.remainderAfter("type", "type"))
  }
}
