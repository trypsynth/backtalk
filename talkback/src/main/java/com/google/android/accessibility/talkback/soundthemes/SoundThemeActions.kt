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

package com.google.android.accessibility.talkback.soundthemes

import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.accessibility.talkback.R
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Saving a theme as a file and removing it, for the screens that list themes. Create it when the
 * fragment is created, as it registers for the result of the file picker.
 */
class SoundThemeActions(
  private val fragment: Fragment,
  private val prefs: () -> SharedPreferences,
  /** Called after a theme is removed. */
  private val onRemoved: () -> Unit,
) {
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  // The theme being saved as a file, kept in case the screen is recreated meanwhile.
  private var exporting: String? = null

  private val exportFile: ActivityResultLauncher<String> =
    fragment.registerForActivityResult(ActivityResultContracts.CreateDocument(MIME_ZIP)) { uri ->
      val id = exporting
      exporting = null
      if (uri != null && id != null) export(id, uri)
    }

  fun restore(state: Bundle?) {
    exporting = state?.getString(STATE_EXPORTING)
  }

  fun save(state: Bundle) {
    state.putString(STATE_EXPORTING, exporting)
  }

  fun shutdown() {
    executor.shutdown()
  }

  fun exportTheme(theme: SoundTheme) {
    exporting = theme.id
    try {
      exportFile.launch("${SoundThemesFragment.nameOf(fragment.requireContext(), theme)}.zip")
    } catch (e: ActivityNotFoundException) {
      // Watches have no document picker.
      exporting = null
      showMessage(fragment.getString(R.string.sound_theme_no_picker))
    }
  }

  fun confirmRemove(theme: SoundTheme) {
    if (theme.isBuiltIn) return
    val context = fragment.requireContext()
    AlertDialog.Builder(context)
      .setMessage(
        fragment.getString(
          R.string.sound_theme_remove_confirm,
          SoundThemesFragment.nameOf(context, theme),
        )
      )
      .setPositiveButton(R.string.sound_theme_remove_button) { _, _ ->
        SoundThemes.delete(context, prefs(), theme.id)
        onRemoved()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  fun showMessage(message: CharSequence) {
    AlertDialog.Builder(fragment.requireContext())
      .setMessage(message)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  private fun export(id: String, uri: Uri) {
    val context = fragment.requireContext().applicationContext
    val activity = fragment.requireActivity()
    val prefs = prefs()
    executor.execute {
      val count =
        try {
          context.contentResolver.openOutputStream(uri)?.use {
            SoundThemes.export(context, prefs, id, it)
          }
        } catch (e: IOException) {
          null
        } catch (e: SecurityException) {
          null
        }
      activity.runOnUiThread {
        if (!fragment.isAdded) return@runOnUiThread
        showMessage(
          if (count == null) fragment.getString(R.string.sound_theme_export_failed)
          else fragment.resources.getQuantityString(R.plurals.sound_theme_exported, count, count)
        )
      }
    }
  }

  companion object {
    private const val STATE_EXPORTING = "exporting"
    private const val MIME_ZIP = "application/zip"

    /** Opens the preference screen [target] of [from], with the string [extras] as arguments. */
    @JvmStatic
    fun open(from: PreferenceFragmentCompat, target: Class<*>, extras: Map<String, String> = emptyMap()) {
      val preference =
        Preference(from.requireContext()).apply {
          fragment = target.name
          extras.forEach { (key, value) -> this.extras.putString(key, value) }
        }
      from.onPreferenceTreeClick(preference)
    }
  }
}
