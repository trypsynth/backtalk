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
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import android.text.style.StyleSpan
import android.text.style.TtsSpan
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.Performance.EventId
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
    RecordingTts.utteranceIds.clear()
  }

  @After fun tearDown() {
    failover.shutdown()
    NumbersAsDigits.setEnabled(false)
    RecordingTts.calls.clear()
    RecordingTts.utteranceIds.clear()
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
    assertEquals(2, RecordingTts.calls.size)
    val joined = StringBuilder()
    for (call in RecordingTts.calls) {
      assertTrue(call.length <= TextToSpeech.getMaxSpeechInputLength())
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
    ReflectionHelpers.setField(failover, "speakInPhrases", true)
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
    val samples = mapOf("$1,500.00" to "$ 1 , 5 0 0 . 0 0", "-3.4" to "- 3 . 4", "R7,600.00" to "R7 , 6 0 0 . 0 0")
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
    enqueueBehindCache(speech, "digits")
    enqueueBehindCache("following speech", "following")
    assertTrue(RecordingTts.calls.isEmpty())
    assertTrue(cache.playbackLookups.isEmpty())
    assertEquals(0, cache.stops)
    NumbersAsDigits.setEnabled(false)
    finishCachedSpeech()
    val chunks = RecordingTts.calls.dropLast(1)
    val limit = TextToSpeech.getMaxSpeechInputLength()
    assertEquals(listOf(limit, speech.length - limit), chunks.map { it.length })
    assertEquals(speech.toString(), chunks.joinToString(""))
    assertEquals(listOf("digits#chunk0", "digits#chunk1", "following"), RecordingTts.utteranceIds)
    assertEquals("following speech", RecordingTts.calls.last().toString())
    assertTrue(cache.playbackLookups.isEmpty())
  }

  @Test fun expandedSpeechWithinEngineLimitSplitsOnlyWithPhrasesEnabled() {
    val speech = NumbersAsDigits.process("1234567890".repeat(20))!!.text
    assertTrue(speech.length > SpeechChunker.MAX_CHUNK)
    speakInChunks(speech, "no-phrases")
    assertEquals(listOf("no-phrases"), RecordingTts.utteranceIds)
    assertEquals(speech.toString(), RecordingTts.calls.single().toString())

    RecordingTts.calls.clear()
    ReflectionHelpers.setField(failover, "speakInPhrases", true)
    speakInChunks(speech, "phrases")
    assertTrue(RecordingTts.calls.size > 1)
    assertTrue(RecordingTts.calls.all { it.length <= SpeechChunker.MAX_CHUNK })
    assertEquals(speech.toString(), RecordingTts.calls.joinToString(""))
  }

  @Test fun engineLimitIsInclusiveForFeedbackAndPreparedSpeech() {
    val limit = TextToSpeech.getMaxSpeechInputLength()
    val fragment = FeedbackFragment("x".repeat(limit), null)
    val feedback = FeedbackItem(null as EventId?)
    feedback.addFragment(fragment)
    FeedbackProcessingUtils.splitLongText(feedback)
    assertSame(fragment, feedback.fragments.single())

    // One trailing letter gives the digit run an even prepared length without an extra space.
    val atLimit = NumbersAsDigits.process("1".repeat(limit / 2) + "x")!!.text
    val overLimit = NumbersAsDigits.process("1".repeat(limit / 2 + 1))!!.text
    assertEquals(limit, atLimit.length)
    assertEquals(limit + 1, overLimit.length)
    speakInChunks(atLimit, "at-limit")
    speakInChunks(overLimit, "over-limit")

    assertEquals(listOf(limit, limit, 1), RecordingTts.calls.map { it.length })
    assertEquals(listOf("at-limit", "over-limit#chunk0", "over-limit#chunk1"),
      RecordingTts.utteranceIds)
    assertEquals(overLimit.toString(), RecordingTts.calls.drop(1).joinToString(""))
  }

  @Test fun engineLimitDoesNotSplitSupplementaryCharacters() {
    val limit = TextToSpeech.getMaxSpeechInputLength()
    val text = "x".repeat(limit - 1) + "𝟙"
    speakInChunks(text)

    assertEquals(listOf(limit - 1, 2), RecordingTts.calls.map { it.length })
    assertEquals("𝟙", RecordingTts.calls.last().toString())
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

  @Test fun expandedDigitsSkipCacheAndOriginalEntryWorksAfterTogglingOff() {
    val cache = installCache("283482")
    NumbersAsDigits.setEnabled(false)
    speakPrepared("283482", "original")
    finishCachedSpeech("original")

    NumbersAsDigits.setEnabled(true)
    speakPrepared("283482", "expanded")
    NumbersAsDigits.setEnabled(false)
    speakPrepared("283482", "original-again")

    assertEquals(listOf("283482", "283482"), cache.probes)
    assertEquals(listOf("283482", "283482"), cache.playbackLookups)
    assertEquals(listOf(null, "expanded", null), RecordingTts.utteranceIds)
    assertEquals(listOf("", "2 8 3 4 8 2", ""), RecordingTts.calls.map { it.toString() })
  }

  @Test fun unchangedSpeechCanUseCacheWhileDigitSettingIsOn() {
    val cache = installCache("Ordinary speech", "3rd", "6")
    speakPrepared("Ordinary speech", "ordinary")
    finishCachedSpeech("ordinary")
    speakPrepared("3rd", "ordinal")
    finishCachedSpeech("ordinal")

    // A resumed suffix may carry the protection marker without having any inserted spaces.
    speakRequest(NumbersAsDigits.protect("6"), "suffix")

    assertEquals(listOf("Ordinary speech", "3rd", "6"), cache.probes)
    assertEquals(listOf("Ordinary speech", "3rd", "6"), cache.playbackLookups)
    assertEquals(listOf("", "", ""), RecordingTts.calls.map { it.toString() })
  }

  @Test fun interruptingExpandedSpeechStopsCacheAndDiscardsSuspendedSpeech() {
    val cache = installCache("Cached speech")
    val completions = mutableMapOf<String, Boolean>()
    failover.addListener(object : FailoverTextToSpeech.FailoverTtsListener {
      override fun onUtteranceRangeStarted(id: String, start: Int, end: Int) {}

      override fun onUtteranceCompleted(id: String, success: Boolean) {
        completions[id] = success
      }
    })
    speakPrepared("Cached speech", "cached")
    enqueueBehindCache("Queued speech", "queued")
    assertEquals(listOf(""), RecordingTts.calls.map { it.toString() })

    speakPrepared("12", "interrupting")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, cache.stops)
    assertFalse(cache.isSpeaking())
    assertEquals(mapOf("queued" to false, "cached" to false), completions)
    assertEquals(listOf("Cached speech"), cache.playbackLookups)
    assertEquals(listOf(null, "interrupting"), RecordingTts.utteranceIds)
    assertEquals(listOf("", "1 2"), RecordingTts.calls.map { it.toString() })
  }

  @Test fun expandedSpeechSetsLocaleEvenIfItsSpacedTextWouldHitTheCache() {
    val cache = installCache("1 2")
    ShadowTextToSpeech.addLanguageAvailability(Locale.FRANCE)
    ReflectionHelpers.setField(failover, "cachedTtsLocale", Locale.US)
    speakPrepared("12", "expanded", Locale.FRANCE)

    assertTrue(cache.probes.isEmpty())
    assertTrue(cache.playbackLookups.isEmpty())
    assertEquals(Locale.FRANCE, ReflectionHelpers.getField<Locale>(failover, "cachedTtsLocale"))
    assertEquals(listOf("expanded"), RecordingTts.utteranceIds)
  }

  private fun installCache(vararg entries: String): FakeSpeakingCache =
    FakeSpeakingCache(RuntimeEnvironment.getApplication(), false, entries.toSet()).also {
      ReflectionHelpers.setField(failover, "speechCacheManager", it)
    }

  private fun speakPrepared(text: String, id: String, locale: Locale = Locale.US) {
    speakRequest(NumbersAsDigits.process(text)?.text ?: text, id, locale)
  }

  private fun speakRequest(text: CharSequence, id: String, locale: Locale = Locale.US) {
    failover.speak(text, locale, 1f, 1f, mapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to id),
      emptyMap(), 0, 1f, false, true, null)
  }

  @Test fun lateRangeUsesReportedUtteranceAndLeavesActiveCursorAlone() {
    val controller = rangeController()
    val fragment = FeedbackFragment("12345", null)
    fragment.setStartIndexInFeedbackItem(10)
    val iterator = activateFragment(controller, fragment)
    val oldRanges = mutableListOf<Pair<Int, Int>>()
    val activeRanges = mutableListOf<Pair<Int, Int>>()
    controller.setUtteranceRangeStartCallback(0) { start, end -> oldRanges.add(start to end) }
    controller.setUtteranceRangeStartCallback(1) { start, end -> activeRanges.add(start to end) }

    reportRange("talkback_0", 2, 5)

    assertEquals(listOf(2 to 5), oldRanges)
    assertTrue(activeRanges.isEmpty())
    assertEquals("12345", iterator.next()!!.text.toString())
    assertEquals(10, iterator.feedbackItemOffset)
  }

  @Test fun matchingRangesMapBothEndpointsBeforeAndAfterResume() {
    val controller = rangeController()
    val fragment = FeedbackFragment("12345", null)
    fragment.setStartIndexInFeedbackItem(10)
    val iterator = activateFragment(controller, fragment)
    val ranges = mutableListOf<Pair<Int, Int>>()
    controller.setUtteranceRangeStartCallback(1) { start, end -> ranges.add(start to end) }

    reportRange("talkback_1", 4, 5)
    assertEquals(listOf(12 to 13), ranges)
    assertEquals("345", iterator.next()!!.text.toString())
    assertEquals("3 4 5", iterator.prepareSpeechText(fragment.text).toString())

    reportRange("talkback_1", 2, 3)
    assertEquals(listOf(12 to 13, 13 to 14), ranges)
  }

  @Test fun matchingRangesMapAcrossEngineChunkBoundary() {
    val controller = rangeController()
    val fragment = FeedbackFragment("1".repeat(3000), null)
    val iterator = activateFragment(controller, fragment)
    val ranges = mutableListOf<Pair<Int, Int>>()
    controller.setUtteranceRangeStartCallback(1) { start, end -> ranges.add(start to end) }
    speakInChunks(iterator.prepareSpeechText(fragment.text)!!, "talkback_1")
    assertEquals(2, RecordingTts.calls.size)
    val boundary = RecordingTts.calls.first().length
    assertEquals(TextToSpeech.getMaxSpeechInputLength(), boundary)

    reportRange(RecordingTts.utteranceIds[0]!!, boundary - 1, boundary)
    reportRange(RecordingTts.utteranceIds[1]!!, 0, 1)

    assertEquals(listOf(boundary / 2 to (boundary + 1) / 2,
      (boundary + 1) / 2 to (boundary + 2) / 2), ranges)
  }

  @Test fun rangesWithoutAnActiveIteratorStillDispatchThroughTheHandler() {
    val controller = rangeController()
    val ranges = mutableListOf<Pair<Int, Int>>()
    controller.setUtteranceRangeStartCallback(1) { start, end -> ranges.add(start to end) }

    reportRange("talkback_1", 3, 7, drainHandler = false)
    assertTrue(ranges.isEmpty())
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(listOf(3 to 7), ranges)
  }

  @Test fun overlayShowsFragmentTextWhileEngineReceivesPreparedSpeech() {
    val controller = SpeechControllerImpl(RuntimeEnvironment.getApplication(),
      object : SpeechController.Delegate {
        override fun isAudioPlaybackActive() = false
        override fun isMicrophoneActiveAndHeadphoneOff() = false
        override fun isSsbActive() = false
        override fun isPhoneCallActive() = false
        override fun onSpeakingForcedFeedback() {}
      }, null, failover, false)
    val overlay = RecordingOverlay()
    controller.setOverlay(overlay)

    fun assertSpeech(index: Int, original: String, expected: String, event: EventId? = null) {
      val id = "talkback_$index"
      val fragment = FeedbackFragment(original, null)
      val item = FeedbackItem(event).apply {
        setUtteranceId(id)
        addFragment(fragment)
      }
      ReflectionHelpers.setField(controller, "currentFeedbackItem", item)
      ReflectionHelpers.setField(controller, "currentFragmentIterator",
        FeedbackFragmentsIterator(item.fragments.iterator(), id))

      assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(controller, "processNextFragmentInternal"))
      assertSame(fragment.text, overlay.text)
      assertEquals(original, overlay.text.toString())
      assertEquals(event?.eventSubtype, overlay.eventType)
      assertEquals(id, RecordingTts.utteranceIds.last())
      assertEquals(expected, RecordingTts.calls.last().toString())
    }

    val event = EventId(0, Performance.EVENT_TYPE_ACCESSIBILITY, AccessibilityEvent.TYPE_VIEW_FOCUSED)
    assertSpeech(1, "283482", "2 8 3 4 8 2", event)
    val ranges = mutableListOf<Pair<Int, Int>>()
    controller.setUtteranceRangeStartCallback(1) { start, end -> ranges.add(start to end) }
    reportRange("talkback_1", 4, 5)
    assertEquals(listOf(2 to 3), ranges)

    assertSpeech(2, "3rd", "3rd")
    NumbersAsDigits.setEnabled(false)
    assertSpeech(3, "283482", "283482")
  }

  private class RecordingOverlay : TextToSpeechOverlay {
    var text: CharSequence? = null
    var eventType: Int? = null

    override fun displayText(text: CharSequence?) {
      this.text = text
      eventType = null
    }

    override fun displayText(text: CharSequence?, eventType: Int) {
      this.text = text
      this.eventType = eventType
    }

    override fun hide() {}
    override fun show() {}
    override fun onConfigurationChanged() {}
  }

  private fun rangeController() =
    SpeechControllerImpl(RuntimeEnvironment.getApplication(), null, null, failover, false)

  private fun activateFragment(
    controller: SpeechControllerImpl,
    fragment: FeedbackFragment,
  ): FeedbackFragmentsIterator {
    val iterator = FeedbackFragmentsIterator(listOf(fragment).iterator(), "talkback_1")
    iterator.next()
    iterator.prepareSpeechText(fragment.text)
    ReflectionHelpers.setField(controller, "currentFragmentIterator", iterator)
    return iterator
  }

  private fun reportRange(utteranceId: String, start: Int, end: Int, drainHandler: Boolean = true) {
    val progress =
      ReflectionHelpers.getField<UtteranceProgressListener>(failover, "utteranceProgressCallback")
    progress.onRangeStart(utteranceId, start, end, 0)
    if (drainHandler) shadowOf(Looper.getMainLooper()).idle()
  }

  private fun speakInChunks(text: CharSequence, utteranceId: String = "test") {
    ReflectionHelpers.callInstanceMethod<Int>(failover, "speakInChunks",
      ClassParameter.from(CharSequence::class.java, text),
      ClassParameter.from(Int::class.javaPrimitiveType!!, TextToSpeech.QUEUE_ADD),
      ClassParameter.from(Bundle::class.java, Bundle()),
      ClassParameter.from(String::class.java, utteranceId),
      ClassParameter.from(Locale::class.java, Locale.US))
  }

  private fun enqueueBehindCache(text: CharSequence, id: String = "test") {
    ReflectionHelpers.callInstanceMethod<Int>(failover, "speakWithCacheOrTts",
      ClassParameter.from(String::class.java, id),
      ClassParameter.from(CharSequence::class.java, text),
      ClassParameter.from(Int::class.javaPrimitiveType!!, TextToSpeech.QUEUE_ADD),
      ClassParameter.from(Locale::class.java, Locale.US),
      ClassParameter.from(Bundle::class.java, Bundle()))
  }

  private fun finishCachedSpeech(id: String = "cached") {
    val listener = ReflectionHelpers.getField<SpeechCachePlayer.PlaybackStateListener>(failover, "playbackStateListener")
    listener.onStateChanged(id, SpeechCachePlayer.STATE_COMPLETED)
    ReflectionHelpers.getField<FakeSpeakingCache>(failover, "speechCacheManager").markCompleted()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private class FakeSpeakingCache(
    context: Context,
    private var speaking: Boolean = true,
    private val entries: Set<String> = emptySet(),
  ) : SpeechCacheManager(context, workerLooper()) {
    val probes = mutableListOf<String>()
    val playbackLookups = mutableListOf<String>()
    var stops = 0
      private set
    private var playingId: String? = null
    private var listener: SpeechCachePlayer.PlaybackStateListener? = null

    override fun isSpeaking() = speaking

    override fun hasCache(
      id: String, text: CharSequence, locale: Locale, queueMode: Int, pitch: Float, rate: Float,
    ): Boolean {
      probes.add(text.toString())
      return queueMode != TextToSpeech.QUEUE_ADD && text.toString() in entries
    }

    override fun speakWithSpeechCachePlayer(
      id: String, queueMode: Int, text: CharSequence, parameters: Bundle,
      callback: SpeechCachePlayer.PlaybackStateListener, locale: Locale,
    ): Boolean {
      playbackLookups.add(text.toString())
      if (queueMode == TextToSpeech.QUEUE_ADD) return false
      stopSpeaking()
      if (text.toString() !in entries) return false
      speaking = true
      playingId = id
      listener = callback
      return true
    }

    override fun stopSpeaking() {
      if (!speaking) return
      stops++
      speaking = false
      listener?.onStateChanged(playingId, SpeechCachePlayer.STATE_STOPPED)
      listener = null
      playingId = null
    }

    fun markCompleted() {
      speaking = false
      listener = null
      playingId = null
    }

    companion object {
      private fun workerLooper(): Looper = HandlerThread("test-cache").apply { start() }.looper
    }
  }

  @Implements(TextToSpeech::class)
  class RecordingTts : ShadowTextToSpeech() {
    @Implementation
    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, id: String?): Int {
      calls.add(text)
      utteranceIds.add(id)
      return TextToSpeech.SUCCESS
    }
    companion object {
      val calls = mutableListOf<CharSequence>()
      val utteranceIds = mutableListOf<String?>()
    }
  }
}
