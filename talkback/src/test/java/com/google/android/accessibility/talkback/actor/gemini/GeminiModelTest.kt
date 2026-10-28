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

package com.google.android.accessibility.talkback.actor.gemini

import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeminiModelTest {
  @Test
  fun builtInModelIsUsedWithoutAUserModel() {
    val prefs = FakeSharedPreferences()
    assertEquals("", GeminiModel.userModel(prefs))
    assertEquals("gemini-3.5-flash", GeminiModel.effectiveModel(prefs, "gemini-3.5-flash"))
  }

  @Test
  fun userModelWinsOverBuiltInModel() {
    val prefs = FakeSharedPreferences()
    GeminiModel.setUserModel(prefs, "gemini-flash-latest")
    assertEquals("gemini-flash-latest", GeminiModel.effectiveModel(prefs, "gemini-3.5-flash"))
  }

  @Test
  fun aNameCopiedFromAiStudioIsCleanedUp() {
    val prefs = FakeSharedPreferences()
    GeminiModel.setUserModel(prefs, "  models/gemini-3.5-flash \n")
    assertEquals("gemini-3.5-flash", GeminiModel.userModel(prefs))
  }

  @Test
  fun clearingTheFieldGoesBackToTheBuiltInModel() {
    val prefs = FakeSharedPreferences()
    GeminiModel.setUserModel(prefs, "gemini-flash-latest")
    GeminiModel.setUserModel(prefs, "   ")
    assertFalse(prefs.contains(GeminiModel.PREF_KEY))
    assertEquals("gemini-3.5-flash", GeminiModel.effectiveModel(prefs, "gemini-3.5-flash"))
  }
}
