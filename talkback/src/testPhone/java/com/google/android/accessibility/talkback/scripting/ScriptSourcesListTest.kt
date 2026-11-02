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

package com.google.android.accessibility.talkback.scripting

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptSourcesListTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val default = ScriptSource.parse("github.com/trypsynth/backtalk-scripts")!!
  private val other = ScriptSource.parse("github.com/a/b")!!

  private fun addresses(kind: SourceKind = SourceKind.SCRIPTS) =
    ScriptSources.all(context, kind).map { it.address }

  @Test
  fun scriptSourcesStartWithBacktalkScripts() {
    assertEquals(listOf(default.address), addresses())
  }

  @Test
  fun soundThemeSourcesStartEmpty() {
    assertEquals(emptyList<String>(), addresses(SourceKind.SOUND_THEMES))
  }

  @Test
  fun addingAnotherSourceKeepsTheDefault() {
    assertTrue(ScriptSources.add(context, other))

    assertEquals(listOf(default.address, other.address), addresses())
  }

  @Test
  fun theDefaultCountsAsAlreadyAdded() {
    assertFalse(ScriptSources.add(context, default))
  }

  @Test
  fun aRemovedDefaultStaysRemoved() {
    ScriptSources.remove(context, default)

    assertEquals(emptyList<String>(), addresses())
  }

  @Test
  fun aSavedListDoesNotGetTheDefault() {
    ScriptSources.remove(context, default)
    ScriptSources.add(context, other)

    assertEquals(listOf(other.address), addresses())
  }
}
