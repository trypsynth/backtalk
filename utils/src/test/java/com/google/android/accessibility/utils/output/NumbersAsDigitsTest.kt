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

import android.graphics.Typeface
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import android.text.style.StyleSpan
import android.text.style.TtsSpan
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class NumbersAsDigitsTest {
  @After fun resetPreference() { NumbersAsDigits.setEnabled(false) }

  private fun spoken(source: String): String = NumbersAsDigits.process(source, true)?.text?.toString() ?: source

  @Test fun enabledReadsRunsAndLeadingZeros() {
    assertEquals("2 8 3 4 8 2", spoken("283482"))
    assertEquals("0 0 0 1 2 3", spoken("000123"))
    assertEquals("Your code is 9 2 3 8 8 6", spoken("Your code is 923886"))
    assertEquals("5 6 3 7 1 4 4 8 7 4 5 1 1 5 9 4 1 6 3 3", spoken("5637 1448 7451 1594 1633"))
  }

  @Test fun contiguousTwentyDigitTokenIsSpaced() {
    assertEquals("0 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8", spoken("00123456789012345678"))
  }

  @Test fun disabledAndUnchangedInputBypassTransformation() {
    for (source in listOf("", "ordinary text", "1", "1 2", "000123", "$1,500.00", "R7,600.00")) {
      assertNull(NumbersAsDigits.process(source, false))
    }
    for (source in listOf("", "ordinary text", "1", "1 2")) assertNull(NumbersAsDigits.process(source, true))
    assertNull(NumbersAsDigits.process(null, true))
  }

  @Test fun numericDelimitersAreSeparatedAndWhitespaceIsPreserved() {
    assertEquals("- 1 2 , 3 4 . 5 6 / 7 8\n9 0\t1 2", spoken("-12,34.56 / 78\n90\t12"))
  }

  @Test fun requestedDelimiterExamplesAreFormattedWithoutNumberInterpretation() {
    assertEquals("$ 1 , 5 0 0 . 0 0", spoken("$1,500.00"))
    assertEquals("3 , 5 . 2 5", spoken("3,5.25"))
    assertEquals("- 3 . 4", spoken("-3.4"))
    assertEquals("R 7 , 6 0 0 . 0 0", spoken("R7,600.00"))
    assertEquals("+ 0 . 5", spoken("+0.5"))
    assertEquals("− 3 . 4", spoken("−3.4"))
    assertEquals("€ 1 , 2 0", spoken("€1,20"))
  }

  @Test fun onlyDirectDigitBoundariesInTheSelectedCharacterSetAreChanged() {
    assertEquals("Hello, world. R$, -- + .", spoken("Hello, world. R$, -- + ."))
    assertEquals("A 1 2 B", spoken("A12B"))
    assertEquals("USD 7 .", spoken("USD7."))
    assertEquals("(1 2)/3 4:5 6!", spoken("(12)/34:56!"))
    assertEquals("$ 1 2 , text. Then 3 . End!", spoken("$12, text. Then 3. End!"))
    assertEquals("1 2  ,  3 4", spoken("12  ,  34"))
  }

  @Test fun unicodeDigitsAreSeparatedAtCodePointBoundaries() {
    assertEquals("١ ٢ ٣", spoken("١٢٣"))
    assertEquals("𝟙 𝟚", spoken("𝟙𝟚"))
    assertEquals("😀1 2😀", spoken("😀12😀"))
  }

  @Test fun positionsMapBackBeforeInsertedSpacesAndAfterTheNumber() {
    val result = NumbersAsDigits.process("A 12 B", true)!!
    assertEquals("A 1 2 B", result.text.toString())
    val expected = intArrayOf(0, 1, 2, 3, 3, 4, 5, 6)
    expected.forEachIndexed { index, offset -> assertEquals(offset, result.sourceOffset(index)) }
    assertEquals(0, result.sourceOffset(-1))
    assertEquals(6, result.sourceOffset(100))
  }

  @Test fun languageStyleAndPunctuationSpansAreRemappedWithoutMutatingSource() {
    val original = SpannableString("12,34")
    val language = LocaleSpan(Locale.FRENCH)
    val style = StyleSpan(Typeface.BOLD)
    val punctuation = TtsSpan.TextBuilder("comma").build()
    original.setSpan(language, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    original.setSpan(style, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    original.setSpan(punctuation, 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.process(original, true)!!.text as Spanned
    assertEquals("1 2 , 3 4", speech.toString())
    assertEquals(0, speech.getSpanStart(language))
    assertEquals(9, speech.getSpanEnd(language))
    assertEquals(2, speech.getSpanStart(style))
    assertEquals(3, speech.getSpanEnd(style))
    assertEquals(4, speech.getSpanStart(punctuation))
    assertEquals(5, speech.getSpanEnd(punctuation))
    assertEquals("12,34", original.toString())
    assertEquals(1, original.getSpanStart(style))
    assertEquals(2, original.getSpanEnd(style))
  }

  @Test fun delimiterSpansFromPunctuationProcessingKeepTheirExactCharacterRanges() {
    val original = SpannableString("$1,500.00")
    val language = LocaleSpan(Locale.US)
    original.setSpan(language, 0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    for (verbosity in listOf(SpeechCleanupUtils.ALL, SpeechCleanupUtils.MOST, SpeechCleanupUtils.SOME)) {
      val source = SpannableString(original)
      for (index in listOf(0, 2, 6)) {
        val name = SpeechCleanupUtils.characterToName(RuntimeEnvironment.getApplication(), source[index], verbosity)
        if (name != null) source.setSpan(TtsSpan.TextBuilder(name).build(), index, index + 1,
          Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
      }
      val result = NumbersAsDigits.process(source, true)!!
      val speech = result.text as Spanned
      assertEquals("$ 1 , 5 0 0 . 0 0", speech.toString())
      assertEquals(17, speech.getSpanEnd(language))
      for (punctuation in source.getSpans(0, source.length, TtsSpan::class.java)) {
        val index = source.getSpanStart(punctuation)
        assertEquals(index * 2, speech.getSpanStart(punctuation))
        assertEquals(index * 2 + 1, speech.getSpanEnd(punctuation))
        assertEquals(Spanned.SPAN_INCLUSIVE_EXCLUSIVE, speech.getSpanFlags(punctuation))
      }
      val expected = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9)
      expected.forEachIndexed { index, offset -> assertEquals(offset, result.sourceOffset(index)) }
      assertEquals("$1,500.00", source.toString())
      assertEquals(9, source.getSpanEnd(language))
    }
  }

  @Test fun delimiterResumeKeepsOriginalCursorAndPreparesRemainingSuffix() {
    NumbersAsDigits.setEnabled(true)
    val fragment = FeedbackFragment("$1,500.00", null)
    val iterator = iterator(fragment)
    iterator.next()
    assertEquals("$ 1 , 5 0 0 . 0 0", iterator.prepareSpeechText(fragment.text).toString())
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(4), iterator.sourceOffset(5))
    iterator.next()
    assertEquals(",500.00", fragment.text.toString())
    assertEquals(2, iterator.feedbackItemOffset)
    assertEquals(", 5 0 0 . 0 0", iterator.prepareSpeechText(fragment.text).toString())
  }

  @Test fun conflictingNumberSpanIsOverriddenOnlyInTheSpeechCopy() {
    val original = SpannableString("000123")
    val number = TtsSpan.CardinalBuilder("123").build()
    original.setSpan(number, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.process(original, true)!!.text as Spanned
    assertEquals("0 0 0 1 2 3", speech.toString())
    assertEquals(-1, speech.getSpanStart(number))
    assertEquals(0, original.getSpanStart(number))
    assertEquals(6, original.getSpanEnd(number))
  }

  @Test fun existingDigitSpanUsedByColorAnnouncementsRemains() {
    val original = SpannableString("00FF")
    val digits = TtsSpan.DigitsBuilder("00FF").build()
    original.setSpan(digits, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.process(original, true)!!.text as Spanned
    assertEquals("0 0 FF", speech.toString())
    assertEquals(0, speech.getSpanStart(digits))
    assertEquals(6, speech.getSpanEnd(digits))
    assertEquals("00FF", digits.args.getString(TtsSpan.ARG_DIGITS))
  }

  @Test fun singleDigitAndExplicitReplacementSpansRemain() {
    val original = SpannableString("123")
    val oneDigit = TtsSpan.CardinalBuilder("2").build()
    val replacement = TtsSpan.TextBuilder("existing speech replacement").build()
    original.setSpan(oneDigit, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    original.setSpan(replacement, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.process(original, true)!!.text as Spanned
    assertEquals(2, speech.getSpanStart(oneDigit))
    assertEquals(3, speech.getSpanEnd(oneDigit))
    assertEquals(0, speech.getSpanStart(replacement))
    assertEquals(5, speech.getSpanEnd(replacement))
  }

  @Test fun resumeUsesOriginalDigitPositionAndRebuildsSuffixMapping() {
    NumbersAsDigits.setEnabled(true)
    val fragment = FeedbackFragment("Code 00123", null)
    val iterator = iterator(fragment)
    assertSame(fragment, iterator.next())
    assertEquals("Code 0 0 1 2 3", iterator.prepareSpeechText(fragment.text).toString())
    val start = iterator.sourceOffset(7)
    assertEquals(6, start)
    iterator.onFragmentRangeStarted("test", start, iterator.sourceOffset(8))
    assertSame(fragment, iterator.next())
    assertEquals("0123", fragment.text.toString())
    assertEquals(6, iterator.feedbackItemOffset)
    assertTrue(iterator.nextIsContinuation())
    assertEquals("0 1 2 3", iterator.prepareSpeechText(fragment.text).toString())
    assertEquals(2, iterator.sourceOffset(4))
  }

  @Test fun preferenceToggleDoesNotChangeMappingOfAlreadySubmittedSpeech() {
    NumbersAsDigits.setEnabled(true)
    val fragment = FeedbackFragment("123", null)
    val iterator = iterator(fragment)
    iterator.next()
    iterator.prepareSpeechText(fragment.text)
    val saved = iterator.deepCopy()
    NumbersAsDigits.setEnabled(false)
    assertEquals(2, iterator.sourceOffset(4))
    assertSame(fragment.text, iterator.prepareSpeechText(fragment.text))
    assertEquals(2, iterator.sourceOffset(2))
    assertEquals(2, saved.sourceOffset(4))
  }

  @Test fun resumedLastDigitDoesNotReplayTheOriginalDigitsArgument() { assertResumedArgumentIsNotReplayed(false) }
  @Test fun resumedSuffixDoesNotReplayTheOriginalTextArgument() { assertResumedArgumentIsNotReplayed(true) }

  private fun assertResumedArgumentIsNotReplayed(textArgument: Boolean) {
    NumbersAsDigits.setEnabled(true)
    val original = SpannableString("123456")
    val instruction = if (textArgument) TtsSpan.TextBuilder("123456").build() else TtsSpan.DigitsBuilder("123456").build()
    val language = LocaleSpan(Locale.US)
    original.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    original.setSpan(language, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val fragment = FeedbackFragment(original, null)
    val iterator = iterator(fragment)
    iterator.next()
    assertEquals("1 2 3 4 5 6", iterator.prepareSpeechText(fragment.text).toString())
    assertEquals(2, (fragment.text as Spanned).getSpans(0, 6, Any::class.java).size)
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(10), iterator.sourceOffset(11))
    iterator.next()
    assertEquals(5, iterator.feedbackItemOffset)
    assertEquals("6", fragment.text.toString())
    val speech = iterator.prepareSpeechText(fragment.text) as Spanned
    assertEquals("6", speech.toString())
    assertEquals(0, speech.getSpans(0, 1, TtsSpan::class.java).size)
    assertEquals(0, speech.getSpanStart(language))
    assertEquals(1, speech.getSpanEnd(language))
    assertEquals(1, iterator.sourceOffset(1))
    assertEquals(0, original.getSpanStart(instruction))
    assertEquals(6, original.getSpanEnd(instruction))
  }

  @Test fun resumeAtAReplacementBoundaryRetainsTheCompleteInstruction() {
    NumbersAsDigits.setEnabled(true)
    val source = SpannableString("12 ABC")
    val complete = TtsSpan.TextBuilder("fully retained").build()
    source.setSpan(complete, 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val fragment = FeedbackFragment(source, null)
    val iterator = iterator(fragment)
    iterator.next()
    assertEquals("1 2 ABC", iterator.prepareSpeechText(fragment.text).toString())
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(4), iterator.sourceOffset(7))
    iterator.next()
    val remaining = iterator.prepareSpeechText(fragment.text) as Spanned
    assertEquals("ABC", remaining.toString())
    assertEquals(3, iterator.feedbackItemOffset)
    assertEquals(0, remaining.getSpanStart(complete))
    assertEquals(3, remaining.getSpanEnd(complete))
    assertEquals("fully retained", complete.args.getString(TtsSpan.ARG_TEXT))
  }

  @Test fun aPlainStringFragmentCanResumeAfterDigitPreparation() {
    NumbersAsDigits.setEnabled(true)
    val fragment = FeedbackFragment("123456", null)
    fragment.text = "123456"
    val iterator = iterator(fragment)
    iterator.next()
    iterator.prepareSpeechText(fragment.text)
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(10), iterator.sourceOffset(11))
    iterator.next()
    assertEquals("6", iterator.prepareSpeechText(fragment.text).toString())
    assertEquals(5, iterator.feedbackItemOffset)
  }

  @Test fun disabledResumeKeepsTheExistingSpanAndTextBehaviour() {
    NumbersAsDigits.setEnabled(false)
    val source = SpannableString("123456")
    val instruction = TtsSpan.TextBuilder("existing replacement").build()
    source.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val fragment = FeedbackFragment(source, null)
    val iterator = iterator(fragment)
    iterator.next()
    assertSame(fragment.text, iterator.prepareSpeechText(fragment.text))
    iterator.onFragmentRangeStarted("test", 5, 6)
    iterator.next()
    val suffix = iterator.prepareSpeechText(fragment.text) as Spanned
    assertEquals("6", suffix.toString())
    assertEquals(0, suffix.getSpanStart(instruction))
    assertEquals(1, suffix.getSpanEnd(instruction))
    assertEquals("existing replacement", instruction.args.getString(TtsSpan.ARG_TEXT))
  }

  @Test fun expandedTextCanBeChunkedWithinTheEngineLimit() {
    val original = "1".repeat(TextToSpeech.getMaxSpeechInputLength() - 1)
    val speech = NumbersAsDigits.process(original, true)!!.text
    assertTrue(speech.length > TextToSpeech.getMaxSpeechInputLength())
    val starts = SpeechChunker.chunkStarts(speech, Locale.US)
    assertTrue(starts.size > 1)
    val joined = StringBuilder()
    for (i in starts.indices) {
      val end = if (i + 1 < starts.size) starts[i + 1] else speech.length
      assertTrue(end - starts[i] < TextToSpeech.getMaxSpeechInputLength())
      joined.append(speech.subSequence(starts[i], end))
    }
    assertEquals(speech.toString(), joined.toString())
  }

  private fun iterator(fragment: FeedbackFragment) = FeedbackFragmentsIterator(listOf(fragment).iterator(), "test")
}
