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

import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentenceTextSegmentIteratorTest {
  private lateinit var defaultLocale: Locale

  @Before
  fun setUp() {
    defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun tearDown() {
    Locale.setDefault(defaultLocale)
  }

  /** Every sentence, moving forward from the start as a user would. */
  private fun forward(text: CharSequence): List<String> {
    val iterator = SentenceTextSegmentIterator(text)
    val sentences = mutableListOf<String>()
    var cursor = 0
    while (true) {
      val range = iterator.following(cursor) ?: break
      sentences += text.subSequence(range[0], range[1]).toString()
      cursor = range[1]
    }
    return sentences
  }

  /** Every sentence, moving back from the end. */
  private fun backward(text: CharSequence): List<String> {
    val iterator = SentenceTextSegmentIterator(text)
    val sentences = mutableListOf<String>()
    var cursor = text.length
    while (true) {
      val range = iterator.preceding(cursor) ?: break
      sentences += text.subSequence(range[0], range[1]).toString()
      cursor = range[0]
    }
    return sentences
  }

  @Test
  fun sentencesEndAtFullStopsQuestionsAndExclamations() {
    val text = "It rained. Did it stop? Not yet! Maybe tomorrow."

    assertEquals(listOf("It rained.", "Did it stop?", "Not yet!", "Maybe tomorrow."), forward(text))
    assertEquals(forward(text).reversed(), backward(text))
  }

  @Test
  fun anAbbreviationDoesNotEndASentence() {
    assertEquals(
      listOf("Mr. Smith went home.", "He slept."),
      forward("Mr. Smith went home. He slept."),
    )
  }

  @Test
  fun aLowercaseWordAfterAFullStopDoesNotStartASentence() {
    assertEquals(
      listOf("Bring fruit, e.g. apples or pears, to the picnic.", "It starts at noon."),
      forward("Bring fruit, e.g. apples or pears, to the picnic. It starts at noon."),
    )
  }

  @Test
  fun chineseFullStopsEndSentences() {
    assertEquals(listOf("你好。", "今天很好！", "你呢？"), forward("你好。今天很好！你呢？"))
  }

  @Test
  fun emojiStayWhole() {
    val text = "I like pizza 🍕. Me too 😀!"
    val sentences = forward(text)

    assertEquals(listOf("I like pizza 🍕.", "Me too 😀!"), sentences)
    val iterator = SentenceTextSegmentIterator(text)
    val first = iterator.following(0)!!
    assertTrue(Character.isLowSurrogate(text[first[1] - 2]))
  }

  @Test
  fun theSpaceBetweenSentencesBelongsToNeither() {
    assertEquals(
      listOf("First.", "Second."),
      forward("  First.   \n\n  Second.  "),
    )
  }

  @Test
  fun textThatIsOnlySpacesHasNoSentences() {
    val iterator = SentenceTextSegmentIterator(" \n\t　 ")

    assertNull(iterator.following(0))
    assertNull(iterator.preceding(5))
  }

  @Test
  fun thereIsNothingAfterTheEndOrBeforeTheStart() {
    val text = "One. Two."
    val iterator = SentenceTextSegmentIterator(text)

    assertNull(iterator.following(text.length))
    assertNull(iterator.preceding(0))
    assertEquals(listOf(0, 4), iterator.following(0)!!.toList())
    assertEquals(listOf(5, 9), iterator.preceding(text.length)!!.toList())
  }

  @Test
  fun aMoveFromInsideASentenceGoesToTheNextOrPreviousWholeOne() {
    val text = "One two. Three four. Five."
    val iterator = SentenceTextSegmentIterator(text)
    val insideThree = text.indexOf("four")

    assertEquals("Five.", text.substring(iterator.following(insideThree)!!.let { it[0] until it[1] }))
    assertEquals("One two.", text.substring(iterator.preceding(insideThree)!!.let { it[0] until it[1] }))
  }

  @Test
  fun theLanguageComesFromTheFirstLocaleSpan() {
    val text = SpannableString("Bonjour. Hallo.")
    text.setSpan(LocaleSpan(Locale.GERMAN), 9, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text.setSpan(LocaleSpan(Locale.FRENCH), 0, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

    assertEquals(Locale.FRENCH, SentenceTextSegmentIterator.localeOf(text))
    assertEquals(Locale.US, SentenceTextSegmentIterator.localeOf("No spans."))
  }

  @Test
  fun theSentenceGranularityGetsTheSentenceIterator() {
    assertTrue(
      GranularityIterator.getIteratorForGranularity("One. Two.", CursorGranularity.SENTENCE_BIT)
        is SentenceTextSegmentIterator
    )
  }

  @Test
  fun sentencesAreSupportedOnlyWhenTheMaskHasTheirBit() {
    val supported = mutableListOf<CursorGranularity>()

    CursorGranularity.extractFromMask(CursorGranularity.SENTENCE_BIT, false, null, supported)
    assertTrue(CursorGranularity.SENTENCE in supported)
    assertTrue(CursorGranularity.SENTENCE.isMicroGranularity)

    CursorGranularity.extractFromMask(CursorGranularity.WORD.value, false, null, supported)
    assertTrue(CursorGranularity.SENTENCE !in supported)
  }
}
