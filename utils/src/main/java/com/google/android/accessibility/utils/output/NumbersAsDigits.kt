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

/** Separates digits and adjacent delimiters in a speech-only copy, retaining source positions. */
object NumbersAsDigits {
  @Volatile private var enabled = false

  @JvmStatic
  fun setEnabled(value: Boolean) {
    enabled = value
  }

  @JvmStatic
  fun process(source: CharSequence?): Result? = process(source, enabled)

  /** Null means unchanged; disabled speech keeps its existing path and source object. */
  @JvmStatic
  fun process(source: CharSequence?, enabled: Boolean): Result? {
    if (!enabled || source == null || source.length < 2) return null
    val length = source.length
    val insertBefore = BooleanArray(length + 1)
    var spaces = 0
    var previous = -1
    var index = 0
    while (index < length) {
      val point = Character.codePointAt(source, index)
      if ((Character.isDigit(previous) && isNumericNeighbour(point)) ||
        (Character.isDigit(point) && isNumericNeighbour(previous))) {
        insertBefore[index] = true
        spaces++
      }
      previous = point
      index += Character.charCount(point)
    }
    if (spaces == 0) return null

    val output = StringBuilder(length + spaces)
    val sourceToSpeech = IntArray(length + 1)
    val speechToSource = IntArray(length + spaces + 1)
    for (i in 0 until length) {
      if (insertBefore[i]) {
        output.append(' ')
        speechToSource[output.length] = i
      }
      sourceToSpeech[i] = output.length
      output.append(source[i])
      speechToSource[output.length] = i + 1
    }
    sourceToSpeech[length] = output.length
    var speech: CharSequence = output.toString()
    if (source is Spanned) {
      val copy = SpannableString(speech)
      for (span in source.getSpans(0, length, Any::class.java)) {
        val start = source.getSpanStart(span)
        val end = source.getSpanEnd(span)
        val mappedStart = sourceToSpeech[start]
        val mappedEnd = sourceToSpeech[end] - if (insertBefore[end]) 1 else 0
        // Numeric interpretations would undo spacing. Explicit wording, punctuation,
        // language, formatting and digits instructions remain on the private speech copy.
        if (span is TtsSpan && conflictsWithSpacing(span) && mappedEnd - mappedStart > end - start) {
          continue
        }
        copy.setSpan(span, mappedStart, maxOf(mappedStart, mappedEnd), source.getSpanFlags(span))
      }
      speech = copy
    }
    return Result(protect(speech), speechToSource)
  }

  /** Tags a private copy so protection follows queued speech and resumed suffixes. */
  @JvmStatic
  fun protect(text: CharSequence): CharSequence {
    if (isPreparedText(text)) return text
    return SpannableString(text).apply {
      setSpan(PreparedSpeechSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }

  @JvmStatic
  fun isPreparedText(text: CharSequence?): Boolean =
    text is Spanned && text.getSpans(0, text.length, PreparedSpeechSpan::class.java).isNotEmpty()

  @JvmStatic
  fun subSequenceForSpeech(text: CharSequence, start: Int, end: Int): CharSequence =
    subSequenceForSpeech(text, start, end, isPreparedText(text))

  /** Partial replacement arguments cannot be split by offsets: use their visible text instead. */
  @JvmStatic
  fun subSequenceForSpeech(
    text: CharSequence, start: Int, end: Int, preparedSpeech: Boolean,
  ): CharSequence {
    val slice = text.subSequence(start, end)
    if (!preparedSpeech || text !is Spanned) return slice
    var copy: SpannableString? = null
    for (span in text.getSpans(start, end, TtsSpan::class.java)) {
      if (!span.args.isEmpty && (text.getSpanStart(span) < start || text.getSpanEnd(span) > end)) {
        val speech = copy ?: SpannableString(slice).also { copy = it }
        speech.removeSpan(span)
      }
    }
    return copy ?: slice
  }

  private class PreparedSpeechSpan

  /** Character boundaries only: no currency, decimal, sign or identifier parsing. */
  private fun isNumericNeighbour(point: Int): Boolean =
    Character.isLetterOrDigit(point) || Character.getType(point) == Character.CURRENCY_SYMBOL.toInt() ||
      point == ','.code || point == '.'.code || point == '+'.code || point == '-'.code || point == 0x2212

  private fun conflictsWithSpacing(span: TtsSpan): Boolean = when (span.type) {
    TtsSpan.TYPE_CARDINAL, TtsSpan.TYPE_ORDINAL, TtsSpan.TYPE_DECIMAL, TtsSpan.TYPE_FRACTION,
    TtsSpan.TYPE_MEASURE, TtsSpan.TYPE_MONEY, TtsSpan.TYPE_DATE, TtsSpan.TYPE_TIME,
    TtsSpan.TYPE_TELEPHONE -> true
    else -> false
  }

  /** Immutable mapping for this exact speech request, including any resumed suffix. */
  class Result(@JvmField val text: CharSequence, private val speechToSource: IntArray) {
    fun sourceOffset(speechOffset: Int): Int =
      speechToSource[speechOffset.coerceIn(0, speechToSource.lastIndex)]
  }
}
