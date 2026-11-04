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

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LocaleSpan;
import android.text.style.StyleSpan;
import android.text.style.TtsSpan;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class NumbersAsDigitsTest {
  @After public void resetPreference() {
    NumbersAsDigits.setEnabled(false);
  }

  private String spoken(String source) {
    NumbersAsDigits.Result result = NumbersAsDigits.process(source, true);
    return result == null ? source : result.text.toString();
  }

  @Test public void enabledReadsRunsAndLeadingZeros() {
    assertEquals("2 8 3 4 8 2", spoken("283482"));
    assertEquals("0 0 0 1 2 3", spoken("000123"));
    assertEquals("Your code is 9 2 3 8 8 6", spoken("Your code is 923886"));
    assertEquals("5 6 3 7 1 4 4 8 7 4 5 1 1 5 9 4 1 6 3 3",
        spoken("5637 1448 7451 1594 1633"));
  }

  @Test public void disabledAndUnchangedInputBypassTransformation() {
    for (String source : Arrays.asList("", "ordinary text", "1", "1 2", "000123", "$1,500.00", "R7,600.00")) {
      assertNull(NumbersAsDigits.process(source, false));
    }
    for (String source : Arrays.asList("", "ordinary text", "1", "1 2")) {
      assertNull(NumbersAsDigits.process(source, true));
    }
    assertNull(NumbersAsDigits.process(null, true));
  }

  @Test public void numericDelimitersAreSeparatedAndWhitespaceIsPreserved() {
    assertEquals("- 1 2 , 3 4 . 5 6 / 7 8\n9 0\t1 2",
        spoken("-12,34.56 / 78\n90\t12"));
  }

  @Test public void requestedDelimiterExamplesAreFormattedWithoutNumberInterpretation() {
    assertEquals("$ 1 , 5 0 0 . 0 0", spoken("$1,500.00"));
    assertEquals("3 , 5 . 2 5", spoken("3,5.25"));
    assertEquals("- 3 . 4", spoken("-3.4"));
    assertEquals("R 7 , 6 0 0 . 0 0", spoken("R7,600.00"));
    assertEquals("+ 0 . 5", spoken("+0.5"));
    assertEquals("− 3 . 4", spoken("−3.4"));
    assertEquals("€ 1 , 2 0", spoken("€1,20"));
  }

  @Test public void onlyDirectDigitBoundariesInTheSelectedCharacterSetAreChanged() {
    assertEquals("Hello, world. R$, -- + .", spoken("Hello, world. R$, -- + ."));
    assertEquals("A 1 2 B", spoken("A12B"));
    assertEquals("USD 7 .", spoken("USD7."));
    assertEquals("(1 2)/3 4:5 6!", spoken("(12)/34:56!"));
    assertEquals("$ 1 2 , text. Then 3 . End!", spoken("$12, text. Then 3. End!"));
    assertEquals("1 2  ,  3 4", spoken("12  ,  34"));
  }

  @Test public void unicodeDigitsAreSeparatedAtCodePointBoundaries() {
    assertEquals("١ ٢ ٣", spoken("١٢٣"));
    assertEquals("𝟙 𝟚", spoken("𝟙𝟚"));
    assertEquals("😀1 2😀", spoken("😀12😀"));
  }

  @Test public void positionsMapBackBeforeInsertedSpacesAndAfterTheNumber() {
    NumbersAsDigits.Result result = NumbersAsDigits.process("A 12 B", true);
    assertEquals("A 1 2 B", result.text.toString());
    int[] expected = {0, 1, 2, 3, 3, 4, 5, 6};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], result.sourceOffset(i));
    }
    assertEquals(0, result.sourceOffset(-1));
    assertEquals(6, result.sourceOffset(100));
  }

  @Test public void languageStyleAndPunctuationSpansAreRemappedWithoutMutatingSource() {
    SpannableString original = new SpannableString("12,34");
    LocaleSpan language = new LocaleSpan(Locale.FRENCH);
    StyleSpan style = new StyleSpan(Typeface.BOLD);
    TtsSpan punctuation = new TtsSpan.TextBuilder("comma").build();
    original.setSpan(language, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    original.setSpan(style, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    original.setSpan(punctuation, 2, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    Spanned speech = (Spanned) NumbersAsDigits.process(original, true).text;
    assertEquals("1 2 , 3 4", speech.toString());
    assertEquals(0, speech.getSpanStart(language));
    assertEquals(9, speech.getSpanEnd(language));
    assertEquals(2, speech.getSpanStart(style));
    assertEquals(3, speech.getSpanEnd(style));
    assertEquals(4, speech.getSpanStart(punctuation));
    assertEquals(5, speech.getSpanEnd(punctuation));
    assertEquals("12,34", original.toString());
    assertEquals(1, original.getSpanStart(style));
    assertEquals(2, original.getSpanEnd(style));
  }

  @Test public void delimiterSpansFromPunctuationProcessingKeepTheirExactCharacterRanges() {
    SpannableString original = new SpannableString("$1,500.00");
    LocaleSpan language = new LocaleSpan(Locale.US);
    original.setSpan(language, 0, original.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    for (int verbosity : new int[] {SpeechCleanupUtils.ALL, SpeechCleanupUtils.MOST,
        SpeechCleanupUtils.SOME}) {
      SpannableString source = new SpannableString(original);
      // makeSpeakablePunctuation uses the same lookup and single-character TTS spans.
      for (int index : new int[] {0, 2, 6}) {
        String spokenName = SpeechCleanupUtils.characterToName(
            RuntimeEnvironment.getApplication(), source.charAt(index), verbosity);
        if (spokenName != null) {
          source.setSpan(new TtsSpan.TextBuilder(spokenName).build(), index, index + 1,
              Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        }
      }
      NumbersAsDigits.Result result = NumbersAsDigits.process(source, true);
      Spanned speech = (Spanned) result.text;
      assertEquals("$ 1 , 5 0 0 . 0 0", speech.toString());
      assertEquals(17, speech.getSpanEnd(language));
      for (TtsSpan punctuation : source.getSpans(0, source.length(), TtsSpan.class)) {
        int index = source.getSpanStart(punctuation);
        assertEquals(index * 2, speech.getSpanStart(punctuation));
        assertEquals(index * 2 + 1, speech.getSpanEnd(punctuation));
        assertEquals(Spanned.SPAN_INCLUSIVE_EXCLUSIVE, speech.getSpanFlags(punctuation));
      }
      int[] expected = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9};
      for (int i = 0; i < expected.length; i++) {
        assertEquals(expected[i], result.sourceOffset(i));
      }
      assertEquals("$1,500.00", source.toString());
      assertEquals(9, source.getSpanEnd(language));
    }
  }

  @Test public void delimiterResumeKeepsOriginalCursorAndPreparesRemainingSuffix() {
    NumbersAsDigits.setEnabled(true);
    FeedbackFragment fragment = new FeedbackFragment("$1,500.00", null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    assertEquals("$ 1 , 5 0 0 . 0 0", iterator.prepareSpeechText(fragment.getText()).toString());
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(4), iterator.sourceOffset(5));
    iterator.next();
    assertEquals(",500.00", fragment.getText().toString());
    assertEquals(2, iterator.getFeedbackItemOffset());
    assertEquals(", 5 0 0 . 0 0", iterator.prepareSpeechText(fragment.getText()).toString());
  }

  @Test public void conflictingNumberSpanIsOverriddenOnlyInTheSpeechCopy() {
    SpannableString original = new SpannableString("000123");
    TtsSpan number = new TtsSpan.CardinalBuilder("123").build();
    original.setSpan(number, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    Spanned speech = (Spanned) NumbersAsDigits.process(original, true).text;
    assertEquals("0 0 0 1 2 3", speech.toString());
    assertEquals(-1, speech.getSpanStart(number));
    assertEquals(0, original.getSpanStart(number));
    assertEquals(6, original.getSpanEnd(number));
  }

  @Test public void existingDigitSpanUsedByColorAnnouncementsRemains() {
    SpannableString original = new SpannableString("00FF");
    TtsSpan digits = new TtsSpan.DigitsBuilder("00FF").build();
    original.setSpan(digits, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    Spanned speech = (Spanned) NumbersAsDigits.process(original, true).text;
    assertEquals("0 0 FF", speech.toString());
    assertEquals(0, speech.getSpanStart(digits));
    assertEquals(6, speech.getSpanEnd(digits));
    assertEquals("00FF", digits.getArgs().getString(TtsSpan.ARG_DIGITS));
  }

  @Test public void singleDigitAndExplicitReplacementSpansRemain() {
    SpannableString original = new SpannableString("123");
    TtsSpan oneDigit = new TtsSpan.CardinalBuilder("2").build();
    TtsSpan replacement = new TtsSpan.TextBuilder("existing speech replacement").build();
    original.setSpan(oneDigit, 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    original.setSpan(replacement, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    Spanned speech = (Spanned) NumbersAsDigits.process(original, true).text;
    assertEquals(2, speech.getSpanStart(oneDigit));
    assertEquals(3, speech.getSpanEnd(oneDigit));
    assertEquals(0, speech.getSpanStart(replacement));
    assertEquals(5, speech.getSpanEnd(replacement));
  }

  @Test public void resumeUsesOriginalDigitPositionAndRebuildsSuffixMapping() {
    NumbersAsDigits.setEnabled(true);
    FeedbackFragment fragment = new FeedbackFragment("Code 00123", null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    assertSame(fragment, iterator.next());
    assertEquals("Code 0 0 1 2 3", iterator.prepareSpeechText(fragment.getText()).toString());
    // The engine begins the second zero at speech offset 7, original offset 6.
    int start = iterator.sourceOffset(7);
    assertEquals(6, start);
    iterator.onFragmentRangeStarted("test", start, iterator.sourceOffset(8));
    assertSame(fragment, iterator.next());
    assertEquals("0123", fragment.getText().toString());
    assertEquals(6, iterator.getFeedbackItemOffset());
    assertTrue(iterator.nextIsContinuation());
    assertEquals("0 1 2 3", iterator.prepareSpeechText(fragment.getText()).toString());
    assertEquals(2, iterator.sourceOffset(4));
  }

  @Test public void preferenceToggleDoesNotChangeMappingOfAlreadySubmittedSpeech() {
    NumbersAsDigits.setEnabled(true);
    FeedbackFragment fragment = new FeedbackFragment("123", null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    iterator.prepareSpeechText(fragment.getText());
    FeedbackFragmentsIterator saved = iterator.deepCopy();
    NumbersAsDigits.setEnabled(false);
    assertEquals(2, iterator.sourceOffset(4));
    assertSame(fragment.getText(), iterator.prepareSpeechText(fragment.getText()));
    assertEquals(2, iterator.sourceOffset(2));
    assertEquals(2, saved.sourceOffset(4));
  }

  @Test public void resumedLastDigitDoesNotReplayTheOriginalDigitsArgument() {
    assertResumedArgumentIsNotReplayed(false);
  }

  @Test public void resumedSuffixDoesNotReplayTheOriginalTextArgument() {
    assertResumedArgumentIsNotReplayed(true);
  }

  private void assertResumedArgumentIsNotReplayed(boolean textArgument) {
    NumbersAsDigits.setEnabled(true);
    SpannableString original = new SpannableString("123456");
    TtsSpan instruction = textArgument ? new TtsSpan.TextBuilder("123456").build()
        : new TtsSpan.DigitsBuilder("123456").build();
    LocaleSpan language = new LocaleSpan(Locale.US);
    original.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    original.setSpan(language, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    FeedbackFragment fragment = new FeedbackFragment(original, null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    assertEquals("1 2 3 4 5 6", iterator.prepareSpeechText(fragment.getText()).toString());
    // Preparing speech must not attach private metadata to stored source/history text.
    assertEquals(2, ((Spanned) fragment.getText()).getSpans(0, 6, Object.class).length);
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(10), iterator.sourceOffset(11));
    iterator.next();
    assertEquals(5, iterator.getFeedbackItemOffset());
    assertEquals("6", fragment.getText().toString());
    Spanned speech = (Spanned) iterator.prepareSpeechText(fragment.getText());
    assertEquals("6", speech.toString());
    assertEquals(0, speech.getSpans(0, 1, TtsSpan.class).length);
    assertEquals(0, speech.getSpanStart(language));
    assertEquals(1, speech.getSpanEnd(language));
    assertEquals(1, iterator.sourceOffset(1));
    assertEquals(0, original.getSpanStart(instruction));
    assertEquals(6, original.getSpanEnd(instruction));
  }

  @Test public void resumeAtAReplacementBoundaryRetainsTheCompleteInstruction() {
    NumbersAsDigits.setEnabled(true);
    SpannableString source = new SpannableString("12 ABC");
    TtsSpan complete = new TtsSpan.TextBuilder("fully retained").build();
    source.setSpan(complete, 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    FeedbackFragment fragment = new FeedbackFragment(source, null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    assertEquals("1 2 ABC", iterator.prepareSpeechText(fragment.getText()).toString());
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(4), iterator.sourceOffset(7));
    iterator.next();
    Spanned remaining = (Spanned) iterator.prepareSpeechText(fragment.getText());
    assertEquals("ABC", remaining.toString());
    assertEquals(3, iterator.getFeedbackItemOffset());
    assertEquals(0, remaining.getSpanStart(complete));
    assertEquals(3, remaining.getSpanEnd(complete));
    assertEquals("fully retained", complete.getArgs().getString(TtsSpan.ARG_TEXT));
  }

  @Test public void aPlainStringFragmentCanResumeAfterDigitPreparation() {
    NumbersAsDigits.setEnabled(true);
    FeedbackFragment fragment = new FeedbackFragment("123456", null);
    fragment.setText("123456");
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    iterator.prepareSpeechText(fragment.getText());
    iterator.onFragmentRangeStarted("test", iterator.sourceOffset(10), iterator.sourceOffset(11));
    iterator.next();
    assertEquals("6", iterator.prepareSpeechText(fragment.getText()).toString());
    assertEquals(5, iterator.getFeedbackItemOffset());
  }

  @Test public void disabledResumeKeepsTheExistingSpanAndTextBehaviour() {
    NumbersAsDigits.setEnabled(false);
    SpannableString source = new SpannableString("123456");
    TtsSpan instruction = new TtsSpan.TextBuilder("existing replacement").build();
    source.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    FeedbackFragment fragment = new FeedbackFragment(source, null);
    FeedbackFragmentsIterator iterator =
        new FeedbackFragmentsIterator(Arrays.asList(fragment).iterator(), "test");
    iterator.next();
    assertSame(fragment.getText(), iterator.prepareSpeechText(fragment.getText()));
    iterator.onFragmentRangeStarted("test", 5, 6);
    iterator.next();
    Spanned suffix = (Spanned) iterator.prepareSpeechText(fragment.getText());
    assertEquals("6", suffix.toString());
    assertEquals(0, suffix.getSpanStart(instruction));
    assertEquals(1, suffix.getSpanEnd(instruction));
    assertEquals("existing replacement", instruction.getArgs().getString(TtsSpan.ARG_TEXT));
  }

  @Test public void expandedTextCanBeChunkedWithinTheEngineLimit() {
    String original = "1".repeat(TextToSpeech.getMaxSpeechInputLength() - 1);
    CharSequence speech = NumbersAsDigits.process(original, true).text;
    assertTrue(speech.length() > TextToSpeech.getMaxSpeechInputLength());
    List<Integer> starts = SpeechChunker.chunkStarts(speech, Locale.US);
    assertTrue(starts.size() > 1);
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1) : speech.length();
      assertTrue(end - starts.get(i) < TextToSpeech.getMaxSpeechInputLength());
      joined.append(speech.subSequence(starts.get(i), end));
    }
    assertEquals(speech.toString(), joined.toString());
  }
}
