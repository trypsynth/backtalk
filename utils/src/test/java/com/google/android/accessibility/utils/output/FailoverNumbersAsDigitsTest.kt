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
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TtsSpan
import com.google.android.accessibility.utils.output.NumbersAsDigitsTest.Companion.assertDigits
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

/** Captures the actual CharSequence supplied to Android's TTS API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], shadows = [FailoverNumbersAsDigitsTest.RecordingTts::class])
class FailoverNumbersAsDigitsTest {
  private lateinit var failover: FailoverTextToSpeech

  @Before fun setup() {
    val context = RuntimeEnvironment.getApplication()
    failover = FailoverTextToSpeech(context, false)
    ReflectionHelpers.setField(failover, "tts", TextToSpeech(context) {})
    ReflectionHelpers.setField(failover, "lowLatencyAudio", false)
    ReflectionHelpers.setField(failover, "speakInPhrases", false)
    VoiceProfiles.setActive(null)
    NumbersAsDigits.setEnabled(true)
    RecordingTts.calls.clear()
  }

  @After fun cleanup() {
    failover.shutdown()
    NumbersAsDigits.setEnabled(false)
    VoiceProfiles.setActive(null)
    RecordingTts.calls.clear()
  }

  @Test fun finalSpeakReceivesSpanAndUnchangedText() {
    val source = "PIN 283482, token 00123456789012345678"
    submit(source, "test")
    assertEquals(1, RecordingTts.calls.size)
    val text = RecordingTts.calls.single()
    assertEquals(source, text.toString())
    assertDigits(text, 4, 10)
    assertDigits(text, 18, 38)
  }

  @Test fun twentyDigitTokenRetainsItsFullSpanAtSubmission() {
    submit("00123456789012345678", "twenty")
    assertDigits(RecordingTts.calls.single(), 0, 20)
  }

  @Test fun thirtyTwoDigitsRetainTheirFullSpanAtSubmission() {
    submit("1".repeat(32), "thirty-two")
    assertDigits(RecordingTts.calls.single(), 0, 32)
  }

  @Test fun thirtyThreeDigitsReachTheEngineEntirelyUnannotated() {
    val source = "1".repeat(33)
    submit(source, "thirty-three")
    assertSame(source, RecordingTts.calls.single())
  }

  @Test fun feedbackProcessingPreservesOriginalTextAndSpanUntilSubmission() {
    val source = "PIN 283482"
    val item = feedback(source)
    assertEquals(1, item.fragments.size)
    val fragment = item.fragments.single().text
    assertEquals(source, fragment.toString())
    submitPrepared(fragment, "feedback")
    assertEquals(source, RecordingTts.calls.single().toString())
    assertDigits(RecordingTts.calls.single(), 4, 10)
  }

  @Test fun feedbackSplittingCannotAnnotateTheShortTailOfAnOverLimitRun() {
    val source = "1".repeat(TextToSpeech.getMaxSpeechInputLength() + 20)
    val item = feedback(source)
    assertTrue(item.fragments.size > 1)
    for ((index, fragment) in item.fragments.withIndex()) {
      submitPrepared(fragment.text, "fragment-$index")
    }
    assertEquals(source, RecordingTts.calls.joinToString(""))
    RecordingTts.calls.forEach { assertNoDigitsSpan(it) }
  }

  @Test fun explicitApplicationInstructionSurvivesAtTheSpeakBoundary() {
    val source = SpannableString("283482")
    val instruction = TtsSpan.CardinalBuilder(283482).build()
    source.setSpan(instruction, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    submit(source, "explicit")
    assertSame(source, RecordingTts.calls.single())
    assertSame(instruction, (RecordingTts.calls.single() as Spanned)
      .getSpans(0, 6, TtsSpan::class.java).single())
  }

  @Test fun liveDisableRestoresOrdinarySubmissionWithoutOffsetMapping() {
    submit("283482", "on")
    NumbersAsDigits.setEnabled(false)
    val plain = "283482"
    submit(plain, "off")
    assertDigits(RecordingTts.calls[0], 0, 6)
    assertSame(plain, RecordingTts.calls[1])
  }

  @Test fun chunkArgumentsDescribeOnlyTheSliceOfAnEligibleOriginalRun() {
    ReflectionHelpers.setField(failover, "speakInPhrases", true)
    val source = "X".repeat(140) + "1".repeat(32) + "Y".repeat(30)
    submit(source, "chunked")
    assertTrue(RecordingTts.calls.size > 1)
    for (call in RecordingTts.calls) {
      val start = call.indexOfFirst { it == '1' }
      if (start >= 0) assertDigits(call, start, call.indexOfLast { it == '1' } + 1)
    }
    assertEquals(source, RecordingTts.calls.joinToString(""))
  }

  @Test fun phraseSplittingCannotMakeAThirtyThreeDigitRunEligible() {
    ReflectionHelpers.setField(failover, "speakInPhrases", true)
    val source = "X".repeat(140) + "1".repeat(33) + "Y".repeat(30)
    submit(source, "over-limit-chunks")
    assertTrue(RecordingTts.calls.size > 1)
    assertEquals(source, RecordingTts.calls.joinToString(""))
    RecordingTts.calls.forEach { assertNoDigitsSpan(it) }
  }

  @Test fun pausedSuffixUsesOnlyItsOwnDigitsWithoutReplayingTheOriginalNumber() {
    val prepared = NumbersAsDigits.apply("283482")!!
    submitPrepared(prepared.subSequence(2, 6), "resumed")
    assertEquals("3482", RecordingTts.calls.single().toString())
    assertDigits(RecordingTts.calls.single(), 0, 4)
    assertDigits(prepared, 0, 6)
  }

  @Test fun numericCacheLookupHasSpansAndUnrelatedTextKeepsItsOriginalObject() {
    val cache = installCache()
    submit("283482", "numeric")
    assertDigits(cache.checked, 0, 6)
    assertDigits(RecordingTts.calls.single(), 0, 6)
    val ordinary = "ordinary text"
    submit(ordinary, "plain")
    assertSame(ordinary, cache.checked)
    NumbersAsDigits.setEnabled(false)
    val number = "283482"
    submit(number, "disabled")
    assertSame(number, cache.checked)
  }

  @Test fun speechSuspendedBehindCacheStillReachesTheEngineWithDigitsSpans() {
    val cache = installCache()
    cache.speaking = true
    submit("283482", "suspended")
    assertTrue(RecordingTts.calls.isEmpty())
    cache.speaking = false
    ReflectionHelpers.callInstanceMethod<Unit>(failover, "speakSuspendQueue")
    shadowOf(Looper.getMainLooper()).idle()
    assertDigits(RecordingTts.calls.single(), 0, 6)
  }

  private fun feedback(source: CharSequence): FeedbackItem =
    FeedbackProcessingUtils.generateFeedbackItemFromInput(
      RuntimeEnvironment.getApplication(), NumbersAsDigits.apply(SpannableString(source)), false,
      SpeechControllerImpl.InlineFormattingHistory(null), 0, 0, true,
      null, null, 0, 0, null, null, null,
    )

  private fun assertNoDigitsSpan(text: CharSequence) {
    if (text is Spanned) assertEquals(0, text.getSpans(0, text.length, TtsSpan::class.java).size)
  }

  private fun installCache(): RecordingCache {
    val worker = HandlerThread("digits-cache-test").apply { start() }
    return RecordingCache(RuntimeEnvironment.getApplication(), worker.looper).also {
      ReflectionHelpers.setField(failover, "speechCacheManager", it)
    }
  }

  private class RecordingCache(context: Context, looper: Looper) : SpeechCacheManager(context, looper) {
    var speaking = false
    var checked: CharSequence? = null
    override fun isSpeaking() = speaking
    override fun speakWithSpeechCachePlayer(
      id: String?, queue: Int, text: CharSequence, params: Bundle,
      callback: SpeechCachePlayer.PlaybackStateListener, locale: Locale?,
    ): Boolean {
      checked = text
      return false
    }
  }

  private fun submit(text: CharSequence, id: String) {
    submitPrepared(NumbersAsDigits.apply(text)!!, id)
  }

  private fun submitPrepared(text: CharSequence, id: String) {
    val result = ReflectionHelpers.callInstanceMethod<Int>(failover, "speakWithCacheOrTts",
      ClassParameter.from(String::class.java, id),
      ClassParameter.from(CharSequence::class.java, text),
      ClassParameter.from(Int::class.javaPrimitiveType!!, TextToSpeech.QUEUE_ADD),
      ClassParameter.from(Locale::class.java, Locale.US),
      ClassParameter.from(Bundle::class.java, Bundle()),
    )
    assertEquals(TextToSpeech.SUCCESS, result)
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
