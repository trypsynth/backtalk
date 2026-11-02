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

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for fuzzy phrase matching, normalisation, and intent extraction
 * in Backtalk voice commands.
 */
object VoiceCommandFuzzyMatcher {

  private val POLITE_PREFIX_REGEX = Regex(
    "^(?:please|can you|could you|would you|hey backtalk|hey talkback|backtalk(?!\\s+settings?)|talkback(?!\\s+settings?))\\s+",
    RegexOption.IGNORE_CASE
  )

  private val PUNCTUATION_REGEX = Regex("[.,!?;:'\"()_\\-\\/]")
  private val MULTI_SPACE_REGEX = Regex("\\s+")

  /**
   * Normalises user-spoken command text:
   * 1. Lowercases the string.
   * 2. Replaces common speech recognizer phonetics/abbreviations.
   * 3. Strips punctuation.
   * 4. Strips conversational prefixes (e.g. "please", "can you", "hey backtalk").
   * 5. Collapses whitespace.
   */
  @JvmStatic
  fun normalize(rawText: String?): String {
    if (rawText.isNullOrBlank()) return ""

    var text = rawText.lowercase(Locale.ROOT)

    // Common speech-to-text / typing quirks
    text = text.replace(Regex("\\bturn of\\b"), "turn off")
    text = text.replace(Regex("\\bswitch on\\b"), "turn on")
    text = text.replace(Regex("\\bswitch off\\b"), "turn off")
    text = text.replace(Regex("\\bd and d\\b"), "dnd")
    text = text.replace(Regex("\\bd\\.n\\.d\\.\\b"), "dnd")

    // Strip punctuation
    text = PUNCTUATION_REGEX.replace(text, " ")
    text = MULTI_SPACE_REGEX.replace(text, " ").trim()

    // Strip conversational prefixes repeatedly
    var stripped = text
    while (true) {
      val next = POLITE_PREFIX_REGEX.replace(stripped, "").trim()
      if (next == stripped) break
      stripped = next
    }

    return stripped
  }

  /**
   * Calculates the Levenshtein distance between two strings.
   */
  @JvmStatic
  fun levenshteinDistance(s1: String, s2: String): Int {
    if (s1 == s2) return 0
    if (s1.isEmpty()) return s2.length
    if (s2.isEmpty()) return s1.length

    val len1 = s1.length
    val len2 = s2.length

    var prevRow = IntArray(len2 + 1) { it }
    var currRow = IntArray(len2 + 1)

    for (i in 0 until len1) {
      currRow[0] = i + 1
      for (j in 0 until len2) {
        val cost = if (s1[i] == s2[j]) 0 else 1
        currRow[j + 1] = min(
          min(currRow[j] + 1, prevRow[j + 1] + 1),
          prevRow[j] + cost
        )
      }
      val temp = prevRow
      prevRow = currRow
      currRow = temp
    }

    return prevRow[len2]
  }

  /**
   * Calculates normalized similarity in range [0.0, 1.0].
   */
  @JvmStatic
  fun similarity(s1: String, s2: String): Double {
    if (s1 == s2) return 1.0
    val maxLen = max(s1.length, s2.length)
    if (maxLen == 0) return 1.0
    val distance = levenshteinDistance(s1, s2)
    return 1.0 - (distance.toDouble() / maxLen)
  }

  /**
   * Checks whether the user input matches the target command phrase fuzzily.
   */
  @JvmStatic
  @JvmOverloads
  fun matchesPhrase(input: String, target: String, threshold: Double = 0.82): Boolean {
    val normInput = normalize(input)
    val normTarget = normalize(target)

    if (normInput.isEmpty() || normTarget.isEmpty()) return false
    if (normInput == normTarget) return true

    // Target mentions backtalk/talkback: require the input to also mention backtalk/talkback.
    // This prevents generic commands like "open settings" from matching "open backtalk settings".
    val targetHasScreenReader = target.contains("backtalk", ignoreCase = true) || target.contains("talkback", ignoreCase = true) || normTarget.contains("backtalk") || normTarget.contains("talkback")
    val inputHasScreenReader = input.contains("backtalk", ignoreCase = true) || input.contains("talkback", ignoreCase = true) || normInput.contains("backtalk") || normInput.contains("talkback")
    if (targetHasScreenReader && !inputHasScreenReader) {
      return false
    }

    // Check if target is a distinct substring/word sequence in input
    if (normInput.contains(normTarget) || normTarget.contains(normInput)) {
      val inputWords = normInput.split(" ")
      val targetWords = normTarget.split(" ")
      val diff = kotlin.math.abs(inputWords.size - targetWords.size)
      if (diff <= 1) return true
    }

    // Token set containment (all target words present in input)
    val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }
    val inputTokens = normInput.split(" ").filter { it.isNotBlank() }.toSet()
    if (targetTokens.isNotEmpty() && targetTokens.all { it in inputTokens }) {
      return true
    }

    // Levenshtein similarity on normalized text
    return similarity(normInput, normTarget) >= threshold
  }

  /**
   * Checks whether the user input matches any target phrase in the list.
   */
  @JvmStatic
  @JvmOverloads
  fun matchesAny(input: String, targets: Iterable<String>, threshold: Double = 0.82): Boolean {
    for (target in targets) {
      if (matchesPhrase(input, target, threshold)) {
        return true
      }
    }
    return false
  }

  /**
   * Extracts the remainder after a prefix if matched fuzzily or exactly.
   * E.g. "open youtube" with prefix "open" -> "youtube".
   */
  @JvmStatic
  fun extractPrefixRemainder(input: String, prefixes: List<String>): String? {
    val normInput = normalize(input)
    for (prefix in prefixes) {
      val normPrefix = normalize(prefix)
      if (normInput.startsWith("$normPrefix ")) {
        val remainder = normInput.substring(normPrefix.length).trim()
        if (remainder.isNotEmpty()) {
          return remainder
        }
      }
    }
    return null
  }
}
