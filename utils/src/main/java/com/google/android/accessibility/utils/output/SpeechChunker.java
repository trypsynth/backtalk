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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits long speech into pieces of about a sentence. Some engines only notice a stop request
 * between the blocks they synthesize, and treat a whole utterance as one block, so stopping a long
 * paragraph took up to 850 ms and held back the next item's speech. A short piece stops quickly,
 * and is queued right behind the one before it.
 *
 * <p>Each piece is a separate request, which many engines start and end with silence and
 * sentence-final intonation, so pieces only end at sentences, where the engine pauses anyway.
 */
public final class SpeechChunker {
  /** Longest piece, in characters. */
  static final int MAX_CHUNK = 150;

  /**
   * Shortest piece, in characters, so short labels stay whole, and engines still get enough of a
   * phrase to read abbreviations and intonation right. A shorter sentence joins the next piece.
   */
  static final int MIN_CHUNK = 25;

  private SpeechChunker() {}

  /**
   * Returns the start offset of each piece of {@code text}, beginning with 0. Text up to {@link
   * #MAX_CHUNK} long is one piece, so that ordinary item descriptions are not split into separate
   * requests. Longer text is cut at sentence boundaries, unless that leaves a piece shorter than
   * {@link #MIN_CHUNK}. A sentence still longer than {@link #MAX_CHUNK} is cut at a space, and only
   * cuts a word when there is none.
   */
  public static List<Integer> chunkStarts(CharSequence text, Locale locale) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    int length = text.length();
    if (length <= MAX_CHUNK) {
      return starts;
    }
    String string = text.toString();
    BreakIterator sentences = BreakIterator.getSentenceInstance(locale);
    sentences.setText(string);

    int start = 0;
    for (int end = sentences.next(); end != BreakIterator.DONE; end = sentences.next()) {
      if (end - start >= MIN_CHUNK && length - end >= MIN_CHUNK) {
        start = addPieces(string, starts, start, end);
      }
    }
    addPieces(string, starts, start, length);
    starts.remove(starts.size() - 1);
    return starts;
  }

  /** Splits only at the engine limit, keeping UTF-16 surrogate pairs together. */
  static List<Integer> chunkStartsAtLimit(CharSequence text, int maxLength) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    int start = 0;
    while (text.length() - start > maxLength) {
      int end = start + maxLength;
      if (Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))) {
        end--;
      }
      starts.add(end);
      start = end;
    }
    return starts;
  }

  /**
   * Adds the starts of the pieces after {@code start}, up to {@code end}, cutting any longer than
   * {@link #MAX_CHUNK}. The last piece is kept at least {@link #MIN_CHUNK} long. Returns {@code
   * end}.
   */
  private static int addPieces(String text, List<Integer> starts, int start, int end) {
    while (end - start > MAX_CHUNK) {
      start = softBreak(text, start, Math.min(start + MAX_CHUNK, end - MIN_CHUNK));
      starts.add(start);
    }
    starts.add(end);
    return end;
  }

  /** Returns where to cut text longer than a piece, just after a space. */
  private static int softBreak(String text, int start, int limit) {
    for (int i = limit - 1; i >= start + MIN_CHUNK; i--) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i + 1;
      }
    }
    return limit;
  }
}
