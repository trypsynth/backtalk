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

/** A spoken request about phone calls. */
sealed interface CallCommand {
  /** Place a call to [target], which is a contact name or a phone number. */
  data class Dial(val target: String, val speaker: Boolean) : CallCommand

  object Answer : CallCommand

  object Decline : CallCommand

  object HangUp : CallCommand
}

/** Understands what a user said about calls. Pure, so it can be unit tested. */
object CallCommandParser {

  private val DIAL_REGEX =
    Regex("^(?:call|dial)\\s+(?:up\\s+)?(.+?)(?:\\s+(?:on|with|using)\\s+(?:the\\s+)?speaker(?:\\s*phone)?)?$")
  private val SPEAKER_REGEX = Regex("\\s(?:on|with|using)\\s+(?:the\\s+)?speaker(?:\\s*phone)?$")

  private val ANSWER = setOf("answer", "answer call", "answer phone", "pick up", "pick up call", "pick up phone", "accept call", "accept")
  private val DECLINE = setOf("decline", "decline call", "reject", "reject call", "decline phone", "reject phone")
  private val HANG_UP = setOf("hang up", "hang up call", "hang up phone", "end call", "end phone call", "disconnect call")

  private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do it", "go ahead", "correct", "call")

  private val DIGIT_WORDS =
    mapOf(
      "zero" to '0', "oh" to '0', "o" to '0', "one" to '1', "two" to '2', "to" to '2', "too" to '2',
      "three" to '3', "four" to '4', "for" to '4', "five" to '5', "six" to '6', "seven" to '7',
      "eight" to '8', "ate" to '8', "nine" to '9')

  private val CHOICE_WORDS =
    mapOf(
      "one" to 1, "first" to 1, "two" to 2, "second" to 2, "to" to 2, "three" to 3, "third" to 3,
      "four" to 4, "fourth" to 4, "for" to 4, "five" to 5, "fifth" to 5)

  /** Returns the call request in [normalized] (already run through the fuzzy matcher's normalize). */
  @JvmStatic
  fun parse(normalized: String): CallCommand? {
    val text = normalized.replace(Regex("\\bthe\\b"), " ").replace(Regex("\\s+"), " ").trim()
    if (text in ANSWER) return CallCommand.Answer
    if (text in DECLINE) return CallCommand.Decline
    if (text in HANG_UP) return CallCommand.HangUp
    // "the" only matters inside a name, so the dial form reads the original.
    val match = DIAL_REGEX.matchEntire(normalized) ?: return null
    val target = match.groupValues[1].trim()
    if (target.isEmpty()) return null
    return CallCommand.Dial(target, SPEAKER_REGEX.containsMatchIn(normalized))
  }

  /**
   * Turns spoken or typed digits into a phone number, or returns null when [text] is not a number.
   * "nine eight seven 6 5 4", "987654" and "+91 98765 43210" all work.
   */
  @JvmStatic
  fun toPhoneNumber(text: String): String? {
    val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val number = StringBuilder()
    for ((index, token) in tokens.withIndex()) {
      when {
        token == "plus" || token == "+" -> if (index == 0) number.append('+') else return null
        token.startsWith("+") && index == 0 && token.drop(1).all { it.isDigit() } -> number.append(token)
        token.all { it.isDigit() } -> number.append(token)
        DIGIT_WORDS.containsKey(token) -> number.append(DIGIT_WORDS.getValue(token))
        else -> return null
      }
    }
    return number.toString().takeIf { it.count { c -> c.isDigit() } >= 3 }
  }

  @JvmStatic fun isYes(normalized: String): Boolean = normalized in YES

  /** Which of the [count] offered options the user picked, counting from 1, or null. */
  @JvmStatic
  fun parseChoice(normalized: String, count: Int): Int? {
    val word = normalized.removePrefix("number ").removePrefix("option ").trim()
    val number = word.toIntOrNull() ?: CHOICE_WORDS[word] ?: return null
    return number.takeIf { it in 1..count }
  }
}

/** A phone contact with one number to dial. */
data class CallContact(val id: Long, val name: String, val number: String)

/** Finds the contacts a spoken name could mean. Pure, so it can be unit tested. */
object CallContactMatcher {
  private const val MIN_SCORE = 0.78
  const val MAX_OPTIONS = 5

  /**
   * Returns the best matching contacts, one per person. One result means the name is clear; more
   * than one means the user has to choose. Callers should list [contacts] with each person's
   * preferred number first.
   */
  @JvmStatic
  fun match(spoken: String, contacts: List<CallContact>): List<CallContact> {
    val query = VoiceCommandFuzzyMatcher.normalize(spoken)
    if (query.isEmpty()) return emptyList()
    val scored =
      contacts
        .map { it to score(query, VoiceCommandFuzzyMatcher.normalize(it.name)) }
        .filter { it.second >= MIN_SCORE }
    val best = scored.maxOfOrNull { it.second } ?: return emptyList()
    return scored
      .filter { it.second >= best - 0.001 }
      .map { it.first }
      .distinctBy { it.id }
      .take(MAX_OPTIONS)
  }

  private fun score(query: String, name: String): Double {
    if (name.isEmpty()) return 0.0
    if (name == query) return 1.0
    val nameWords = name.split(" ")
    if (query.split(" ").all { it in nameWords }) return 0.95
    if (nameWords.any { it.startsWith(query) }) return 0.9
    return maxOf(
      VoiceCommandFuzzyMatcher.similarity(query, name),
      nameWords.maxOf { VoiceCommandFuzzyMatcher.similarity(query, it) } * 0.95)
  }
}
