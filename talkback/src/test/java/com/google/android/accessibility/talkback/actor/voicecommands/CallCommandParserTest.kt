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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCommandParserTest {

  private fun parse(spoken: String) = CallCommandParser.parse(VoiceCommandFuzzyMatcher.normalize(spoken))

  @Test
  fun dialsAContact() {
    assertEquals(CallCommand.Dial("mom", false), parse("Call Mom"))
    assertEquals(CallCommand.Dial("rahul sharma", false), parse("please call Rahul Sharma"))
    assertEquals(CallCommand.Dial("mom", false), parse("dial mom"))
  }

  @Test
  fun dialsOnSpeaker() {
    assertEquals(CallCommand.Dial("mom", true), parse("call Mom on speaker"))
    assertEquals(CallCommand.Dial("mom", true), parse("call mom on the speakerphone"))
    assertEquals(CallCommand.Dial("mom", true), parse("call mom with speaker"))
  }

  @Test
  fun nameContainingTheStaysIntact() {
    assertEquals(CallCommand.Dial("the dentist", false), parse("call the dentist"))
  }

  @Test
  fun answersCalls() {
    for (phrase in listOf("answer the call", "Answer", "pick up the call", "pick up", "accept the call")) {
      assertEquals(phrase, CallCommand.Answer, parse(phrase))
    }
  }

  @Test
  fun declinesCalls() {
    for (phrase in listOf("decline", "decline the call", "reject the call", "reject")) {
      assertEquals(phrase, CallCommand.Decline, parse(phrase))
    }
  }

  @Test
  fun hangsUp() {
    for (phrase in listOf("hang up", "end call", "end the call", "hang up the phone")) {
      assertEquals(phrase, CallCommand.HangUp, parse(phrase))
    }
  }

  @Test
  fun ignoresOtherCommands() {
    assertNull(parse("call"))
    assertNull(parse("open settings"))
    assertNull(parse("battery"))
    assertNull(parse("answer my question about the weather"))
  }

  @Test
  fun readsSpokenDigits() {
    assertEquals("9876543210", CallCommandParser.toPhoneNumber("nine eight seven six five four three two one zero"))
    assertEquals("9876543210", CallCommandParser.toPhoneNumber("9876543210"))
    assertEquals("9876543210", CallCommandParser.toPhoneNumber("98765 43210"))
    assertEquals("100", CallCommandParser.toPhoneNumber("one oh oh"))
  }

  @Test
  fun readsPlusPrefix() {
    assertEquals("+919876543210", CallCommandParser.toPhoneNumber("plus 91 9876543210"))
    assertEquals("+919876543210", CallCommandParser.toPhoneNumber("+91 9876543210"))
  }

  @Test
  fun namesAreNotNumbers() {
    assertNull(CallCommandParser.toPhoneNumber("mom"))
    assertNull(CallCommandParser.toPhoneNumber("rahul two"))
    assertNull(CallCommandParser.toPhoneNumber("12"))
  }

  @Test
  fun onlyAnExplicitYesConfirms() {
    assertTrue(CallCommandParser.isYes("yes"))
    assertTrue(CallCommandParser.isYes("okay"))
    assertFalse(CallCommandParser.isYes("no"))
    assertFalse(CallCommandParser.isYes("yes but not now"))
    assertFalse(CallCommandParser.isYes(""))
  }

  @Test
  fun readsAChoice() {
    assertEquals(2, CallCommandParser.parseChoice("2", 3))
    assertEquals(2, CallCommandParser.parseChoice("two", 3))
    assertEquals(3, CallCommandParser.parseChoice("number three", 3))
    assertEquals(1, CallCommandParser.parseChoice("first", 2))
    assertNull(CallCommandParser.parseChoice("4", 3))
    assertNull(CallCommandParser.parseChoice("0", 3))
    assertNull(CallCommandParser.parseChoice("banana", 3))
  }
}
