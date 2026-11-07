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

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises actual engine requests, including the suspended cache queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], shadows = [FailoverNumbersAsDigitsTest.RecordingTts::class])
class FailoverNumbersAsDigitsTest {
  private lateinit var failover: FailoverTextToSpeech

  @Before fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    failover = FailoverTextToSpeech(context, false)
    ReflectionHelpers.setField(failover, "tts", TextToSpeech(context) {})
    ReflectionHelpers.setField(failover, "lowLatencyAudio", false)
    ReflectionHelpers.setField(failover, "speakInPhrases", false)
    VoiceProfiles.setActive(null)
    NumbersAsDigits.setEnabled(true)
    RecordingTts.calls.clear()
  }

  @After fun tearDown() {
    failover.shutdown()
    NumbersAsDigits.setEnabled(false)
    RecordingTts.calls.clear()
  }

  @Test fun longDigitsArgumentIsNotRepeatedInEveryEngineChunk() { assertLongArgumentIsNotRepeated(false) }
  @Test fun longTextArgumentIsNotRepeatedInEveryEngineChunk() { assertLongArgumentIsNotRepeated(true) }

  private fun assertLongArgumentIsNotRepeated(textArgument: Boolean) {
    val original = "1234567890".repeat(300)
    val source = SpannableString(original)
    val instruction = if (textArgument) TtsSpan.TextBuilder(original).build() else TtsSpan.DigitsBuilder(original).build()
    val language = LocaleSpan(Locale.US)
    val style = StyleSpan(Typeface.BOLD)
    source.setSpan(style, 0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    source.setSpan(instruction, 0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    source.setSpan(language, 0, original.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val speech = NumbersAsDigits.process(source, true)!!.text
    speakInChunks(speech)
    assertTrue(RecordingTts.calls.size > 1)
    val joined = StringBuilder()
    for (call in RecordingTts.calls) {
      assertTrue(call.length < TextToSpeech.getMaxSpeechInputLength())
      val spanned = call as Spanned
      assertEquals(0, spanned.getSpans(0, call.length, TtsSpan::class.java).size)
      assertEquals(0, spanned.getSpanStart(language))
      assertEquals(call.length, spanned.getSpanEnd(language))
      assertEquals(0, spanned.getSpanStart(style))
      assertEquals(call.length, spanned.getSpanEnd(style))
      joined.append(call)
    }
    assertEquals(speech.toString(), joined.toString())
    assertEquals(original, source.toString())
    assertEquals(0, source.getSpanStart(instruction))
    assertEquals(original.length, source.getSpanEnd(instruction))
  }

  @Test fun completeInstructionsSurviveButAnUnexpandedCrossingSpanDoesNot() {
    val source = SpannableString("x".repeat(180) + ", " + "1".repeat(2500))
    val replacement = TtsSpan.TextBuilder("alternate wording").build()
    val comma = TtsSpan.TextBuilder("comma").build()
    val complete = TtsSpan.TextBuilder("fully retained").build()
    source.setSpan(complete, 300, 310, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    source.setSpan(replacement, 0, 180, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    source.setSpan(comma, 180, 181, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    speakInChunks(NumbersAsDigits.process(source, true)!!.text)
    var commas = 0
    var completeReplacements = 0
    for (call in RecordingTts.calls) {
      val spanned = call as Spanned
      assertEquals(-1, spanned.getSpanStart(replacement))
      if (spanned.getSpanStart(complete) >= 0) {
        completeReplacements++
        assertEquals("fully retained", complete.args.getString(TtsSpan.ARG_TEXT))
      }
      if (spanned.getSpanStart(comma) >= 0) {
        commas++
        assertEquals(",", call.subSequence(spanned.getSpanStart(comma), spanned.getSpanEnd(comma)).toString())
      }
    }
    assertEquals(1, commas)
    assertEquals(1, completeReplacements)
  }

  @Test fun delimiterExtensionReachesTheEngineAsExplicitSpaces() {
    val samples = mapOf("$1,500.00" to "$ 1 , 5 0 0 . 0 0", "-3.4" to "- 3 . 4", "R7,600.00" to "R 7 , 6 0 0 . 0 0")
    for ((source, expected) in samples) {
      RecordingTts.calls.clear()
      speakInChunks(NumbersAsDigits.process(source, true)!!.text)
      assertEquals(expected, RecordingTts.calls.single().toString())
    }
  }

  @Test fun suspendedExpandedSpeechUsesTheLengthGuardEvenAfterSettingIsTurnedOff() {
    val cache = FakeSpeakingCache(RuntimeEnvironment.getApplication())
    ReflectionHelpers.setField(failover, "speechCacheManager", cache)
    val speech = NumbersAsDigits.process("1".repeat(3000), true)!!.text
    enqueueBehindCache(speech)
    assertTrue(RecordingTts.calls.isEmpty())
    NumbersAsDigits.setEnabled(false)
    finishCachedSpeech()
    assertTrue(RecordingTts.calls.size > 1)
    val joined = StringBuilder()
    for (call in RecordingTts.calls) {
      assertTrue(call.length < TextToSpeech.getMaxSpeechInputLength())
      joined.append(call)
    }
    assertEquals(speech.toString(), joined.toString())
  }

  @Test fun suspendedUnpreparedSpeechKeepsItsExistingSingleRequestBehaviour() {
    val cache = FakeSpeakingCache(RuntimeEnvironment.getApplication())
    ReflectionHelpers.setField(failover, "speechCacheManager", cache)
    NumbersAsDigits.setEnabled(false)
    ReflectionHelpers.setField(failover, "speakInPhrases", true)
    val original = "word ".repeat(100)
    enqueueBehindCache(original)
    assertTrue(RecordingTts.calls.isEmpty())
    finishCachedSpeech()
    assertEquals(1, RecordingTts.calls.size)
    assertEquals(original, RecordingTts.calls.single().toString())
  }

  private fun speakInChunks(text: CharSequence) {
    ReflectionHelpers.callInstanceMethod<Int>(failover, "speakInChunks",
      ClassParameter.from(CharSequence::class.java, text),
      ClassParameter.from(Int::class.javaPrimitiveType!!, TextToSpeech.QUEUE_ADD),
      ClassParameter.from(Bundle::class.java, Bundle()),
      ClassParameter.from(String::class.java, "test"),
      ClassParameter.from(Locale::class.java, Locale.US))
  }

  private fun enqueueBehindCache(text: CharSequence) {
    ReflectionHelpers.callInstanceMethod<Int>(failover, "speakWithCacheOrTts",
      ClassParameter.from(String::class.java, "test"),
      ClassParameter.from(CharSequence::class.java, text),
      ClassParameter.from(Int::class.javaPrimitiveType!!, TextToSpeech.QUEUE_ADD),
      ClassParameter.from(Locale::class.java, Locale.US),
      ClassParameter.from(Bundle::class.java, Bundle()))
  }

  private fun finishCachedSpeech() {
    val listener = ReflectionHelpers.getField<SpeechCachePlayer.PlaybackStateListener>(failover, "playbackStateListener")
    listener.onStateChanged("cached", SpeechCachePlayer.STATE_COMPLETED)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private class FakeSpeakingCache(context: Context) : SpeechCacheManager(context, workerLooper()) {
    // Cache completion is posted before playback finally clears its playing flag.
    override fun isSpeaking() = true
    companion object {
      private fun workerLooper(): Looper = HandlerThread("test-cache").apply { start() }.looper
    }
  }

  @Implements(TextToSpeech::class)
  class RecordingTts : ShadowTextToSpeech() {
    @Implementation
    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, id: String?): Int {
      calls.add(text)
      return TextToSpeech.SUCCESS
    }
    companion object {
      val calls = mutableListOf<CharSequence>()
    }
  }
}
