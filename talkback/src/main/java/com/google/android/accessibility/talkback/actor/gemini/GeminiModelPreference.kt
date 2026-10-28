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

import android.text.InputType
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/** The "Gemini model" preference in Automatic descriptions settings. */
object GeminiModelPreference {

  /** Sets up the preference, if the [fragment] has it. */
  @JvmStatic
  fun setUp(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(GeminiModel.PREF_KEY) ?: return
    updateSummary(preference)
    preference.setOnPreferenceClickListener {
      showDialog(fragment, preference)
      true
    }
  }

  private fun updateSummary(preference: Preference) {
    val prefs = SharedPreferencesUtils.getSharedPreferences(preference.context)
    preference.summary = GeminiModel.effectiveModel(prefs, BuildConfig.GEMINI_MODEL)
  }

  private fun showDialog(fragment: PreferenceFragmentCompat, preference: Preference) {
    val context = fragment.requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val field =
      KeyboardSafeEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        hint = BuildConfig.GEMINI_MODEL
        setText(GeminiModel.userModel(prefs))
      }
    // Lines the field up with the dialog's message.
    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container =
      FrameLayout(context).apply {
        setPadding(padding, 0, padding, 0)
        addView(field)
      }
    AlertDialog.Builder(context)
      .setTitle(R.string.title_pref_gemini_model)
      .setMessage(context.getString(R.string.gemini_model_dialog_message, BuildConfig.GEMINI_MODEL))
      .setView(container)
      .setPositiveButton(R.string.gemini_api_key_save) { _, _ ->
        GeminiModel.setUserModel(prefs, field.text.toString())
        updateSummary(preference)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }
}
