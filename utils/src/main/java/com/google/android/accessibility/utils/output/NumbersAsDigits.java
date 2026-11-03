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

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TtsSpan;
import androidx.annotation.Nullable;

/** Separates digits and adjacent delimiters in a speech-only copy, retaining source positions. */
public final class NumbersAsDigits {
  private static volatile boolean enabled;

  public static void setEnabled(boolean value) {
    enabled = value;
  }

  @Nullable
  static Result process(@Nullable CharSequence source) {
    return process(source, enabled);
  }

  /** Returns null when no change is needed, so disabled speech keeps the original object. */
  @Nullable
  static Result process(@Nullable CharSequence source, boolean enabled) {
    if (!enabled || source == null || source.length() < 2) {
      return null;
    }
    int length = source.length();
    boolean[] insertBefore = new boolean[length + 1];
    int spaces = 0;
    int previous = -1;
    for (int i = 0; i < length; ) {
      int codePoint = Character.codePointAt(source, i);
      if ((Character.isDigit(previous) && isNumericNeighbour(codePoint))
          || (Character.isDigit(codePoint) && isNumericNeighbour(previous))) {
        insertBefore[i] = true;
        spaces++;
      }
      previous = codePoint;
      i += Character.charCount(codePoint);
    }
    if (spaces == 0) {
      return null;
    }

    StringBuilder output = new StringBuilder(length + spaces);
    int[] sourceToSpeech = new int[length + 1];
    int[] speechToSource = new int[length + spaces + 1];
    for (int i = 0; i < length; i++) {
      if (insertBefore[i]) {
        output.append(' ');
        speechToSource[output.length()] = i;
      }
      sourceToSpeech[i] = output.length();
      output.append(source.charAt(i));
      speechToSource[output.length()] = i + 1;
    }
    sourceToSpeech[length] = output.length();
    CharSequence speech = output.toString();
    if (source instanceof Spanned spanned) {
      SpannableString copy = new SpannableString(speech);
      for (Object span : spanned.getSpans(0, length, Object.class)) {
        int start = spanned.getSpanStart(span);
        int end = spanned.getSpanEnd(span);
        int mappedStart = sourceToSpeech[start];
        int mappedEnd = sourceToSpeech[end] - (insertBefore[end] ? 1 : 0);
        // Number interpretation would override the spaces. Keep explicit text replacements,
        // punctuation, language, formatting and digit-by-digit spans. Override number readings.
        if (span instanceof TtsSpan ttsSpan && conflictsWithSpacing(ttsSpan)
            && mappedEnd - mappedStart > end - start) {
          continue;
        }
        copy.setSpan(span, mappedStart, Math.max(mappedStart, mappedEnd),
            spanned.getSpanFlags(span));
      }
      speech = copy;
    }
    return new Result(protect(speech), speechToSource);
  }

  /** Tags a private copy so protection follows prepared speech through queues and resume. */
  static CharSequence protect(CharSequence text) {
    if (isPreparedText(text)) {
      return text;
    }
    SpannableString copy = new SpannableString(text);
    copy.setSpan(new PreparedSpeechSpan(), 0, copy.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return copy;
  }

  static boolean isPreparedText(CharSequence text) {
    return text instanceof Spanned spanned
        && spanned.getSpans(0, text.length(), PreparedSpeechSpan.class).length != 0;
  }

  /**
   * Cuts a prepared speech copy without repeating an instruction for the unsliced text.
   * Arbitrary replacement arguments cannot be divided using character offsets, so a partial
   * instruction falls back to the visible text. Complete instructions and other spans survive.
   */
  static CharSequence subSequenceForSpeech(CharSequence text, int start, int end) {
    return subSequenceForSpeech(text, start, end, isPreparedText(text));
  }

  static CharSequence subSequenceForSpeech(
      CharSequence text, int start, int end, boolean preparedSpeech) {
    CharSequence slice = text.subSequence(start, end);
    if (!preparedSpeech || !(text instanceof Spanned spanned)) {
      return slice;
    }
    SpannableString copy = null;
    for (TtsSpan span : spanned.getSpans(start, end, TtsSpan.class)) {
      if (!span.getArgs().isEmpty()
          && (spanned.getSpanStart(span) < start || spanned.getSpanEnd(span) > end)) {
        if (copy == null) {
          copy = new SpannableString(slice);
        }
        copy.removeSpan(span);
      }
    }
    return copy == null ? slice : copy;
  }

  /** Internal metadata only; never attached to accessibility-node text. */
  private static final class PreparedSpeechSpan {}

  /** Character boundaries only: no currency, decimal, sign or identifier recognition. */
  private static boolean isNumericNeighbour(int codePoint) {
    return Character.isLetterOrDigit(codePoint)
        || Character.getType(codePoint) == Character.CURRENCY_SYMBOL
        || codePoint == ','
        || codePoint == '.'
        || codePoint == '+'
        || codePoint == '-'
        || codePoint == '\u2212';
  }

  private static boolean conflictsWithSpacing(TtsSpan span) {
    return switch (span.getType()) {
      case TtsSpan.TYPE_CARDINAL, TtsSpan.TYPE_ORDINAL, TtsSpan.TYPE_DECIMAL,
          TtsSpan.TYPE_FRACTION, TtsSpan.TYPE_MEASURE, TtsSpan.TYPE_MONEY,
          TtsSpan.TYPE_DATE, TtsSpan.TYPE_TIME, TtsSpan.TYPE_TELEPHONE -> true;
      default -> false;
    };
  }

  /** Immutable mapping for this exact speech request, including a resumed fragment's suffix. */
  static final class Result {
    final CharSequence text;
    private final int[] speechToSource;

    Result(CharSequence text, int[] speechToSource) {
      this.text = text;
      this.speechToSource = speechToSource;
    }

    int sourceOffset(int speechOffset) {
      return speechToSource[Math.max(0, Math.min(speechOffset, speechToSource.length - 1))];
    }
  }

  private NumbersAsDigits() {}
}
