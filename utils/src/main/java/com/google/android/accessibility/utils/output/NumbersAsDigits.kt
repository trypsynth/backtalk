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

package com.google.android.accessibility.utils.output

import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan

/** Pronunciation metadata on a speech copy; characters and UTF-16 offsets never change. */
object NumbersAsDigits {
  private const val MAX_DIGITS = 32
  @Volatile private var enabled = false

  @JvmStatic
  fun setEnabled(value: Boolean) {
    enabled = value
  }

  /** Decide eligibility before formatting or chunking can split an over-limit run. */
  @JvmStatic
  fun apply(source: CharSequence?): CharSequence? {
    if (!enabled || source == null) return source
    var speech: SpannableString? = null
    var start = 0
    while (start < source.length) {
      val point = Character.codePointAt(source, start)
      if (!Character.isDigit(point)) {
        start += Character.charCount(point)
        continue
      }
      var end = start
      var count = 0
      while (end < source.length && Character.isDigit(Character.codePointAt(source, end))) {
        end += Character.charCount(Character.codePointAt(source, end))
        count++
      }
      // Skip the entire run, never a 32-digit prefix of a longer number.
      if (count in 2..MAX_DIGITS && !hasInstruction(source, start, end)) {
        val copy = speech ?: SpannableString(source).also { speech = it }
        copy.setSpan(
          DigitsSpan(source.subSequence(start, end).toString()),
          start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
      }
      start = end
    }
    return speech ?: source
  }

  /** Clip only our generated arguments when the existing pipeline slices prepared text. */
  @JvmStatic
  fun forSpeech(source: CharSequence?): CharSequence? {
    if (source !is Spanned) return source
    var speech: SpannableString? = null
    for (span in source.getSpans(0, source.length, DigitsSpan::class.java)) {
      val start = source.getSpanStart(span)
      val end = source.getSpanEnd(span)
      val digits = source.subSequence(start, end).toString()
      if (digits == span.args.getString(TtsSpan.ARG_DIGITS)) continue
      val copy = speech ?: SpannableString(source).also { speech = it }
      copy.removeSpan(span)
      // Formatting must not turn a numeric instruction into an unrelated replacement.
      if (digits.isNotEmpty() && digits.codePoints().allMatch { Character.isDigit(it) } &&
        digits.codePointCount(0, digits.length) <= MAX_DIGITS) {
        copy.setSpan(DigitsSpan(digits), start, end, source.getSpanFlags(span))
      }
    }
    return speech ?: source
  }

  private fun hasInstruction(source: CharSequence, start: Int, end: Int): Boolean =
    source is Spanned && source.getSpans(start, end, TtsSpan::class.java).any {
      source.getSpanStart(it) < end && source.getSpanEnd(it) > start &&
        source.getSpanStart(it) < source.getSpanEnd(it)
    }

  // A standard TYPE_DIGITS span on the wire. The subtype lets us adjust our own sliced
  // instructions without changing explicit application-provided TtsSpan arguments.
  private class DigitsSpan(digits: String) :
    TtsSpan(TtsSpan.TYPE_DIGITS, TtsSpan.DigitsBuilder().setDigits(digits).build().args)
}
