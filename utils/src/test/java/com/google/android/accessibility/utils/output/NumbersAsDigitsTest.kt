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
import android.os.Parcel
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LocaleSpan
import android.text.style.StyleSpan
import android.text.style.TtsSpan
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class NumbersAsDigitsTest {
  @Before fun enable() { NumbersAsDigits.setEnabled(true) }
  @After fun reset() { NumbersAsDigits.setEnabled(false) }

  @Test fun disabledKeepsOriginalObjectAndSpans() {
    val source = SpannableString("283482")
    NumbersAsDigits.setEnabled(false)
    assertSame(source, NumbersAsDigits.apply(source))
  }

  @Test fun sixDigitCodeKeepsTextAndLength() {
    val source = "283482"
    val speech = NumbersAsDigits.apply(source)
    assertEquals(source, speech.toString())
    assertEquals(source.length, speech!!.length)
    assertDigits(speech, 0, source.length)
  }

  @Test fun twentyDigitsAreAnnotatedIncludingLeadingZeros() {
    val source = "00123456789012345678"
    val speech = NumbersAsDigits.apply(source)
    assertEquals(source, speech.toString())
    assertEquals(20, speech!!.length)
    assertDigits(speech, 0, 20)
  }

  @Test fun thirtyTwoDigitsAreAnnotatedInFull() {
    val source = "1".repeat(32)
    assertDigits(NumbersAsDigits.apply(source), 0, 32)
  }

  @Test fun thirtyThreeDigitsAreEntirelyUnannotated() {
    val source = "1".repeat(33)
    assertSame(source, NumbersAsDigits.apply(source))
    val mixed = NumbersAsDigits.apply("283482 $source 654321") as Spanned
    assertDigits(mixed, 0, 6)
    assertEquals(0, mixed.getSpans(7, 40, TtsSpan::class.java).size)
    assertDigits(mixed, 41, 47)
  }

  @Test fun overLimitRunCannotBecomeEligibleAfterSlicing() {
    val source = "1".repeat(33)
    val prepared = NumbersAsDigits.apply(source)!!
    assertSame(source, prepared)
    val suffix = prepared.subSequence(1, 33)
    assertSame(suffix, NumbersAsDigits.forSpeech(suffix))
    assertFalse(suffix is Spanned)
  }

  @Test fun excessiveRunsRemainUnannotatedWithoutHidingLaterEligibleNumbers() {
    val excessive = "1".repeat(TextToSpeech.getMaxSpeechInputLength())
    assertSame(excessive, NumbersAsDigits.apply(excessive))
    val mixed = NumbersAsDigits.apply("$excessive 283482")!!
    assertDigits(mixed, excessive.length + 1, mixed.length)
  }

  @Test fun runsHaveExactIndependentBoundariesIncludingAdjacentLetters() {
    val speech = NumbersAsDigits.apply("OTP 001234, token A12345678901234567890Z, digit 7") as Spanned
    assertEquals(2, speech.getSpans(0, speech.length, TtsSpan::class.java).size)
    assertDigits(speech, 4, 10)
    assertDigits(speech, 19, 39)
  }

  @Test fun compatibleSpansAndInputArePreserved() {
    val source = SpannableString("PIN 283482")
    val style = StyleSpan(Typeface.BOLD)
    val locale = LocaleSpan(Locale.UK)
    source.setSpan(style, 0, 10, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    source.setSpan(locale, 4, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.apply(source) as Spanned
    assertEquals(0, speech.getSpanStart(style))
    assertEquals(10, speech.getSpanEnd(style))
    assertEquals(Spanned.SPAN_INCLUSIVE_INCLUSIVE, speech.getSpanFlags(style))
    assertEquals(4, speech.getSpanStart(locale))
    assertEquals(10, speech.getSpanEnd(locale))
    assertEquals(0, source.getSpans(0, 10, TtsSpan::class.java).size)
    assertDigits(speech, 4, 10)
  }

  @Test fun anyOverlappingExplicitPronunciationWinsWithoutDuplication() {
    for (instruction in listOf(
      TtsSpan.CardinalBuilder(283482).build(), TtsSpan.DigitsBuilder("283482").build(),
      TtsSpan.TextBuilder("application pronunciation").build(),
    )) {
      val source = SpannableString("283482 654321")
      source.setSpan(instruction, 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      val speech = NumbersAsDigits.apply(source) as Spanned
      assertEquals(2, speech.getSpans(0, speech.length, TtsSpan::class.java).size)
      assertEquals(1, speech.getSpanStart(instruction))
      assertEquals(5, speech.getSpanEnd(instruction))
      assertDigits(speech, 7, 13)
    }
  }

  @Test fun adjacentExplicitInstructionDoesNotBlockTheNextRun() {
    val source = SpannableString("x283482")
    source.setSpan(TtsSpan.TextBuilder("ex").build(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    assertDigits(NumbersAsDigits.apply(source), 1, 7)
  }

  @Test fun existingFullRunInstructionKeepsOriginalObject() {
    val source = SpannableString("283482")
    source.setSpan(TtsSpan.CardinalBuilder(283482).build(), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    assertSame(source, NumbersAsDigits.apply(source))
    assertSame(source, NumbersAsDigits.forSpeech(source))
  }

  @Test fun nonNumericAndIsolatedDigitsNeedNoCopy() {
    for (source in listOf("", "ordinary text", "1", "a1b", "1 2 3", "-1.2")) {
      assertSame(source, NumbersAsDigits.apply(source))
    }
    assertNull(NumbersAsDigits.apply(null))
    assertNull(NumbersAsDigits.forSpeech(null))
  }

  @Test fun unicodeDecimalDigitsKeepUtf16BoundariesAndOriginalCharacters() {
    val source = "😀 ١٢ 𝟙𝟚"
    val speech = NumbersAsDigits.apply(source)
    assertEquals(source, speech.toString())
    assertDigits(speech, 3, 5)
    assertDigits(speech, 6, 10)
    assertDigits(NumbersAsDigits.apply("𝟙".repeat(32)), 0, 64)
    val excessive = "𝟙".repeat(33)
    assertSame(excessive, NumbersAsDigits.apply(excessive))
  }

  @Test fun repeatedProcessingDoesNotAddDuplicateSpans() {
    val speech = NumbersAsDigits.apply("283482")
    assertSame(speech, NumbersAsDigits.apply(speech))
    assertSame(speech, NumbersAsDigits.forSpeech(speech))
    assertDigits(speech, 0, 6)
  }

  @Test fun slicingAdjustsOnlyOurOwnArgumentsAndPreservesThePreparedInput() {
    val prepared = NumbersAsDigits.apply("283482")!!
    val suffix = NumbersAsDigits.forSpeech(prepared.subSequence(2, 6))
    assertEquals("3482", suffix.toString())
    assertDigits(suffix, 0, 4)
    assertDigits(prepared, 0, 6)
    val explicit = SpannableString("283482")
    val instruction = TtsSpan.TextBuilder("explicit wording").build()
    explicit.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val sliced = explicit.subSequence(2, 6)
    assertSame(sliced, NumbersAsDigits.forSpeech(sliced))
    assertEquals("explicit wording", instruction.args.getString(TtsSpan.ARG_TEXT))
  }

  @Test fun generatedSpanParcelsAsAnOrdinaryAndroidDigitsSpan() {
    val speech = NumbersAsDigits.forSpeech(NumbersAsDigits.apply("283482"))!!
    val parcel = Parcel.obtain()
    try {
      TextUtils.writeToParcel(speech, parcel, 0)
      parcel.setDataPosition(0)
      assertDigits(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel), 0, 6)
    } finally {
      parcel.recycle()
    }
  }

  companion object {
    fun assertDigits(text: CharSequence?, start: Int, end: Int) {
      assertTrue(text is Spanned)
      val spanned = text as Spanned
      val spans = spanned.getSpans(start, end, TtsSpan::class.java)
      assertEquals(1, spans.size)
      assertEquals(TtsSpan.TYPE_DIGITS, spans[0].type)
      assertEquals(start, spanned.getSpanStart(spans[0]))
      assertEquals(end, spanned.getSpanEnd(spans[0]))
      assertEquals(text.subSequence(start, end).toString(), spans[0].args.getString(TtsSpan.ARG_DIGITS))
    }
  }
}
