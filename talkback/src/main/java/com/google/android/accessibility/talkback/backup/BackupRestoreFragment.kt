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

package com.google.android.accessibility.talkback.backup

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment

/** Saves all settings to a file, and restores them from one. */
class BackupRestoreFragment : TalkbackBaseFragment() {
  private val createBackup =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
      if (uri != null) {
        writeBackup(uri)
      }
    }

  private val openBackup =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        readBackup(uri)
      }
    }

  public override fun getTitle(): CharSequence = getText(R.string.settings_backup_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    val screen = preferenceManager.createPreferenceScreen(context)
    screen.addPreference(
      actionPreference(context, R.string.settings_backup_export, R.string.settings_backup_export_summary) {
        createBackup.launch(SettingsBackup.FILE_NAME)
      }
    )
    screen.addPreference(
      actionPreference(context, R.string.settings_backup_import, R.string.settings_backup_import_summary) {
        openBackup.launch(arrayOf("application/json", "text/plain"))
      }
    )
    preferenceScreen = screen
  }

  private fun actionPreference(context: Context, title: Int, summary: Int, onClick: () -> Unit) =
    Preference(context).apply {
      isPersistent = false
      layoutResource = R.layout.listitem_2texts
      setTitle(title)
      setSummary(summary)
      setOnPreferenceClickListener {
        onClick()
        true
      }
    }

  private fun writeBackup(uri: Uri) {
    val context = requireContext()
    val written =
      try {
        context.contentResolver.openOutputStream(uri)?.use {
          it.write(SettingsBackup.export(SettingsBackup.stores(context)).toByteArray())
        } != null
      } catch (_: java.io.IOException) {
        false
      }
    toast(if (written) R.string.settings_backup_export_done else R.string.settings_backup_export_failed)
  }

  private fun readBackup(uri: Uri) {
    val context = requireContext()
    val json =
      try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
      } catch (_: java.io.IOException) {
        null
      }
    val result =
      if (json == null) SettingsBackup.ImportResult.FAILED
      else SettingsBackup.import(SettingsBackup.stores(context), json)
    when (result) {
      SettingsBackup.ImportResult.FAILED -> toast(R.string.settings_backup_import_failed)
      SettingsBackup.ImportResult.RESTORED -> toast(R.string.settings_backup_import_done)
      // A dialog, because a toast is gone before a screen reader user has heard all of it.
      SettingsBackup.ImportResult.RESTORED_NEEDS_RESTART ->
        AlertDialog.Builder(context)
          .setTitle(R.string.settings_backup_import_done)
          .setMessage(R.string.settings_backup_import_restart)
          .setPositiveButton(android.R.string.ok, null)
          .show()
    }
  }

  private fun toast(message: Int) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
  }
}
