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

import android.content.SharedPreferences

/**
 * The Gemini model that Describe image and Describe screen ask.
 *
 * The user can enter one in Automatic descriptions settings, so that a model Google retires or
 * overloads can be swapped without a new build (#298). Until they do, the model built into the app
 * from local.properties is used.
 */
object GeminiModel {
  const val PREF_KEY = "pref_gemini_model"

  /** The model the user entered, or an empty string. */
  fun userModel(prefs: SharedPreferences): String =
    prefs.getString(PREF_KEY, null)?.let(::clean) ?: ""

  /** Saves the [model], or removes the user's model when it is blank. */
  fun setUserModel(prefs: SharedPreferences, model: String) {
    val cleaned = clean(model)
    if (cleaned.isEmpty()) {
      prefs.edit().remove(PREF_KEY).apply()
    } else {
      prefs.edit().putString(PREF_KEY, cleaned).apply()
    }
  }

  /** The model to use: the user's, or else the [builtInModel]. */
  @JvmStatic
  fun effectiveModel(prefs: SharedPreferences, builtInModel: String): String =
    userModel(prefs).ifEmpty { clean(builtInModel) }

  // AI Studio shows names as "models/gemini-3.5-flash", and the URL already has "models/".
  private fun clean(model: String): String = model.trim().removePrefix("models/").trim()
}
