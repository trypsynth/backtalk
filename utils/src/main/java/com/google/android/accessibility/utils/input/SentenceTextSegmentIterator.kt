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

package com.google.android.accessibility.utils.input

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import android.text.Spanned
import android.text.style.LocaleSpan
import com.google.android.accessibility.utils.input.GranularityIterator.TextSegmentIterator
import java.util.Locale

/**
 * Moves through text by sentence, which apps can't do, so Backtalk finds the sentences itself.
 *
 * Sentences are found by ICU's rules for the text's language: the language of its first
 * [LocaleSpan], or else the device's. These rules know the full stops of other scripts, such as
 * Chinese and Japanese "。", and with `ss=standard` they don't end a sentence after abbreviations
 * such as "Mr." where ICU has a list for the language. The space between sentences belongs to
 * neither, and text that is only spaces is never a sentence. Positions are UTF-16 indices that never
 * fall inside a surrogate pair, so emoji stay whole.
 */
class SentenceTextSegmentIterator(text: CharSequence) : TextSegmentIterator {
  /** Each sentence as its start and end, in order, without the space around it. */
  private val sentences: List<IntArray> = sentencesOf(text)

  /** The first sentence that starts at or after [current]. */
  override fun following(current: Int): IntArray? =
    sentences.firstOrNull { it[0] >= current }?.copyOf()

  /** The last sentence that ends at or before [current]. */
  override fun preceding(current: Int): IntArray? =
    sentences.lastOrNull { it[1] <= current }?.copyOf()

  companion object {
    @JvmStatic
    fun localeOf(text: CharSequence): Locale {
      if (text is Spanned) {
        val spans = text.getSpans(0, text.length, LocaleSpan::class.java)
        spans
          .minByOrNull { text.getSpanStart(it) }
          ?.locale
          ?.let {
            return it
          }
      }
      return Locale.getDefault()
    }

    /** The ICU locale for [locale], asking ICU to keep abbreviations from ending a sentence. */
    private fun sentenceLocale(locale: Locale): ULocale =
      try {
        ULocale.Builder()
          .setLocale(ULocale.forLocale(locale))
          .setUnicodeLocaleKeyword("ss", "standard")
          .build()
      } catch (e: IllegalArgumentException) {
        ULocale.forLocale(locale)
      }

    private fun sentencesOf(text: CharSequence): List<IntArray> {
      if (text.isEmpty()) return emptyList()
      val iterator = BreakIterator.getSentenceInstance(sentenceLocale(localeOf(text)))
      iterator.setText(text.toString())
      val sentences = mutableListOf<IntArray>()
      var start = iterator.first()
      var end = iterator.next()
      while (end != BreakIterator.DONE) {
        var from = start
        var to = end
        // isWhitespace counts no-break and ideographic spaces too.
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from < to) sentences += intArrayOf(from, to)
        start = end
        end = iterator.next()
      }
      return sentences
    }
  }
}
