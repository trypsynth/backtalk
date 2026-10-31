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
import org.junit.Assert.assertTrue
import org.junit.Test

class CallContactMatcherTest {

  private val contacts =
    listOf(
      CallContact(1, "Mom", "111"),
      CallContact(2, "Rahul Sharma", "222"),
      CallContact(3, "Rahul Verma", "333"),
      CallContact(4, "Priya", "444"),
      CallContact(4, "Priya", "555"),
      CallContact(5, "Dr. Mehta", "666"),
      CallContact(6, "Mom Work", "777"))

  private fun numbers(spoken: String) = CallContactMatcher.match(spoken, contacts).map { it.number }

  @Test
  fun exactNameBeatsLongerNames() {
    assertEquals(listOf("111"), numbers("mom"))
  }

  @Test
  fun sharedFirstNameIsAmbiguous() {
    assertEquals(listOf("222", "333"), numbers("rahul"))
  }

  @Test
  fun fullNameIsClear() {
    assertEquals(listOf("222"), numbers("rahul sharma"))
  }

  @Test
  fun aPersonWithTwoNumbersIsOnePerson() {
    assertEquals(listOf("444"), numbers("priya"))
  }

  @Test
  fun toleratesSpeechMistakes() {
    assertEquals(listOf("444"), numbers("pria"))
    assertEquals(listOf("666"), numbers("mehta"))
  }

  @Test
  fun unknownNameMatchesNothing() {
    assertTrue(numbers("zebra").isEmpty())
    assertTrue(numbers("").isEmpty())
  }

  @Test
  fun neverOffersMoreThanTheLimit() {
    val many = (1L..20L).map { CallContact(it, "Sam $it", "$it") }
    assertEquals(CallContactMatcher.MAX_OPTIONS, CallContactMatcher.match("sam", many).size)
  }
}
