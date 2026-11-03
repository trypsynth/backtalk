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
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LocaleSpan;
import android.text.style.StyleSpan;
import android.text.style.TtsSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** Exercises the actual engine requests, including the suspended cache queue. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34}, shadows = FailoverNumbersAsDigitsTest.RecordingTts.class)
public class FailoverNumbersAsDigitsTest {
  private FailoverTextToSpeech failover;

  @Before public void setUp() {
    Context context = RuntimeEnvironment.getApplication();
    failover = new FailoverTextToSpeech(context, false);
    ReflectionHelpers.setField(failover, "tts", new TextToSpeech(context, status -> {}));
    ReflectionHelpers.setField(failover, "lowLatencyAudio", false);
    ReflectionHelpers.setField(failover, "speakInPhrases", false);
    VoiceProfiles.setActive(null);
    NumbersAsDigits.setEnabled(true);
    RecordingTts.calls.clear();
  }

  @After public void tearDown() {
    failover.shutdown();
    NumbersAsDigits.setEnabled(false);
    RecordingTts.calls.clear();
  }

  @Test public void longDigitsArgumentIsNotRepeatedInEveryEngineChunk() {
    assertLongArgumentIsNotRepeated(false);
  }

  @Test public void longTextArgumentIsNotRepeatedInEveryEngineChunk() {
    assertLongArgumentIsNotRepeated(true);
  }

  private void assertLongArgumentIsNotRepeated(boolean textArgument) {
    String original = "1234567890".repeat(300);
    SpannableString source = new SpannableString(original);
    TtsSpan instruction = textArgument ? new TtsSpan.TextBuilder(original).build()
        : new TtsSpan.DigitsBuilder(original).build();
    LocaleSpan language = new LocaleSpan(Locale.US);
    StyleSpan style = new StyleSpan(Typeface.BOLD);
    source.setSpan(style, 0, original.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    source.setSpan(instruction, 0, original.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    source.setSpan(language, 0, original.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    CharSequence speech = NumbersAsDigits.process(source, true).text;
    speakInChunks(speech);
    assertTrue(RecordingTts.calls.size() > 1);
    StringBuilder joined = new StringBuilder();
    for (CharSequence call : RecordingTts.calls) {
      assertTrue(call.length() < TextToSpeech.getMaxSpeechInputLength());
      Spanned spanned = (Spanned) call;
      assertEquals(0, spanned.getSpans(0, call.length(), TtsSpan.class).length);
      assertEquals(0, spanned.getSpanStart(language));
      assertEquals(call.length(), spanned.getSpanEnd(language));
      assertEquals(0, spanned.getSpanStart(style));
      assertEquals(call.length(), spanned.getSpanEnd(style));
      joined.append(call);
    }
    assertEquals(speech.toString(), joined.toString());
    assertEquals(original, source.toString());
    assertEquals(0, source.getSpanStart(instruction));
    assertEquals(original.length(), source.getSpanEnd(instruction));
  }

  @Test public void completeInstructionsSurviveButAnUnexpandedCrossingSpanDoesNot() {
    SpannableString source = new SpannableString("x".repeat(180) + ", " + "1".repeat(2500));
    TtsSpan replacement = new TtsSpan.TextBuilder("alternate wording").build();
    TtsSpan comma = new TtsSpan.TextBuilder("comma").build();
    TtsSpan complete = new TtsSpan.TextBuilder("fully retained").build();
    source.setSpan(complete, 300, 310, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    source.setSpan(replacement, 0, 180, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    source.setSpan(comma, 180, 181, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    speakInChunks(NumbersAsDigits.process(source, true).text);
    int commas = 0;
    int completeReplacements = 0;
    for (CharSequence call : RecordingTts.calls) {
      Spanned spanned = (Spanned) call;
      assertEquals(-1, spanned.getSpanStart(replacement));
      if (spanned.getSpanStart(complete) >= 0) {
        completeReplacements++;
        assertEquals("fully retained", complete.getArgs().getString(TtsSpan.ARG_TEXT));
      }
      if (spanned.getSpanStart(comma) >= 0) {
        commas++;
        assertEquals(",", call.subSequence(spanned.getSpanStart(comma),
            spanned.getSpanEnd(comma)).toString());
      }
    }
    assertEquals(1, commas);
    assertEquals(1, completeReplacements);
  }

  @Test public void suspendedExpandedSpeechUsesTheLengthGuardEvenAfterSettingIsTurnedOff() {
    FakeSpeakingCache cache = new FakeSpeakingCache(RuntimeEnvironment.getApplication());
    ReflectionHelpers.setField(failover, "speechCacheManager", cache);
    CharSequence speech = NumbersAsDigits.process("1".repeat(3000), true).text;
    enqueueBehindCache(speech);
    assertTrue(RecordingTts.calls.isEmpty());
    NumbersAsDigits.setEnabled(false);
    finishCachedSpeech();
    assertTrue(RecordingTts.calls.size() > 1);
    StringBuilder joined = new StringBuilder();
    for (CharSequence call : RecordingTts.calls) {
      assertTrue(call.length() < TextToSpeech.getMaxSpeechInputLength());
      joined.append(call);
    }
    assertEquals(speech.toString(), joined.toString());
  }

  @Test public void suspendedUnpreparedSpeechKeepsItsExistingSingleRequestBehaviour() {
    FakeSpeakingCache cache = new FakeSpeakingCache(RuntimeEnvironment.getApplication());
    ReflectionHelpers.setField(failover, "speechCacheManager", cache);
    NumbersAsDigits.setEnabled(false);
    ReflectionHelpers.setField(failover, "speakInPhrases", true);
    String original = "word ".repeat(100);
    enqueueBehindCache(original);
    assertTrue(RecordingTts.calls.isEmpty());
    finishCachedSpeech();
    assertEquals(1, RecordingTts.calls.size());
    assertEquals(original, RecordingTts.calls.get(0).toString());
  }

  private void speakInChunks(CharSequence text) {
    ReflectionHelpers.callInstanceMethod(failover, "speakInChunks",
        ClassParameter.from(CharSequence.class, text),
        ClassParameter.from(int.class, TextToSpeech.QUEUE_ADD),
        ClassParameter.from(Bundle.class, new Bundle()),
        ClassParameter.from(String.class, "test"),
        ClassParameter.from(Locale.class, Locale.US));
  }

  private void enqueueBehindCache(CharSequence text) {
    ReflectionHelpers.callInstanceMethod(failover, "speakWithCacheOrTts",
        ClassParameter.from(String.class, "test"),
        ClassParameter.from(CharSequence.class, text),
        ClassParameter.from(int.class, TextToSpeech.QUEUE_ADD),
        ClassParameter.from(Locale.class, Locale.US),
        ClassParameter.from(Bundle.class, new Bundle()));
  }

  private void finishCachedSpeech() {
    SpeechCachePlayer.PlaybackStateListener listener =
        ReflectionHelpers.getField(failover, "playbackStateListener");
    listener.onStateChanged("cached", SpeechCachePlayer.STATE_COMPLETED);
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static final class FakeSpeakingCache extends SpeechCacheManager {
    FakeSpeakingCache(Context context) {
      super(context, workerLooper());
    }

    private static Looper workerLooper() {
      HandlerThread worker = new HandlerThread("test-cache");
      worker.start();
      return worker.getLooper();
    }

    @Override public boolean isSpeaking() {
      // Cache completion is posted before playback finally clears its playing flag.
      return true;
    }
  }

  @Implements(TextToSpeech.class)
  public static class RecordingTts extends ShadowTextToSpeech {
    static final List<CharSequence> calls = new ArrayList<>();

    @Implementation
    @Override protected int speak(CharSequence text, int queueMode, Bundle params, String id) {
      calls.add(text);
      return TextToSpeech.SUCCESS;
    }
  }
}
