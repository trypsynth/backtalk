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
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText

/**
 * Manages sound themes: makes new ones, opens the settings of each, and installs more. Themes are
 * installed by [SoundThemeInstallActivity], which also opens theme files shared with Backtalk from
 * other apps. Which theme is in use is chosen in Sound and vibration.
 */
class SoundThemesFragment : TalkbackBaseFragment() {
  private lateinit var prefs: SharedPreferences

  private val actions = SoundThemeActions(this, { prefs }, { rebuild() })

  private val installFile: ActivityResultLauncher<Array<String>> =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) startActivity(SoundThemeInstallActivity.fileIntent(requireContext(), uri))
    }

  public override fun getTitle(): CharSequence = getText(R.string.title_pref_sound_themes)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    actions.restore(savedInstanceState)
    // Like every settings screen, so that nothing is ever saved outside Backtalk's settings.
    preferenceManager.setStorageDeviceProtected()
    preferenceScreen = preferenceManager.createPreferenceScreen(context)
  }

  override fun onResume() {
    super.onResume()
    // A theme may have been installed from another app, or changed on its own screen, meanwhile.
    rebuild()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    actions.save(outState)
  }

  override fun onDestroy() {
    super.onDestroy()
    actions.shutdown()
  }

  private fun rebuild() {
    val context = requireContext()
    val screen = preferenceScreen
    screen.removeAll()
    val active = SoundThemes.activeId(prefs)

    screen.addPreference(
      action(
        context,
        getString(R.string.title_pref_new_sound_theme),
        getString(R.string.summary_pref_new_sound_theme),
      ) {
        askForNewTheme()
      }
    )

    val installed = category(context, getString(R.string.sound_themes_installed_category))
    screen.addPreference(installed)
    for (theme in SoundThemes.installed(context)) {
      val row =
        ThemeRow(
          context,
          theme,
          nameOf(context, theme),
          summaryOf(context, theme, theme.id == active),
          export = { actions.exportTheme(theme) },
          remove = { actions.confirmRemove(theme) },
        )
      row.setOnPreferenceClickListener {
        SoundThemeActions.open(
          this,
          SoundThemeSettingsFragment::class.java,
          mapOf(SoundThemeSettingsFragment.ARG_THEME_ID to theme.id),
        )
        true
      }
      installed.addPreference(row)
    }

    val install = category(context, getString(R.string.sound_themes_install_category))
    screen.addPreference(install)
    install.addPreference(
      action(
        context,
        getString(R.string.title_pref_sound_theme_install_file),
        getString(R.string.summary_pref_sound_theme_install_file),
      ) {
        launchPicker()
      }
    )
    install.addPreference(
      action(
        context,
        getString(R.string.title_pref_sound_theme_install_link),
        getString(R.string.summary_pref_sound_theme_install_link),
      ) {
        askForLink()
      }
    )
    SoundThemeSourcesScreen.screen?.let { sources ->
      install.addPreference(
        action(
          context,
          getString(R.string.title_pref_sound_theme_install_source),
          getString(R.string.summary_pref_sound_theme_install_source),
        ) {
          SoundThemeActions.open(this, sources)
        }
      )
    }
  }

  /** Asks for the details of a new theme, then opens its sounds with the theme in use. */
  private fun askForNewTheme() {
    val context = requireContext()
    fun field(hint: Int, multiLine: Boolean = false) =
      KeyboardSafeEditText(context).apply {
        inputType =
          InputType.TYPE_CLASS_TEXT or
            if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setHint(hint)
      }
    val name = field(R.string.new_theme_name_hint)
    val description = field(R.string.new_theme_description_hint, multiLine = true)
    val author = field(R.string.new_theme_author_hint)
    val controlSounds =
      CheckBox(context).apply {
        setText(R.string.new_theme_control_sounds)
        isChecked = true
      }
    val audio3d =
      Spinner(context).apply {
        adapter =
          ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.pref_control_sounds_3d_entries),
          )
        contentDescription = getString(R.string.title_pref_control_sounds_3d)
      }
    val values = resources.getStringArray(R.array.pref_control_sounds_3d_values)
    val container =
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        listOf(name, description, author, controlSounds, audio3d).forEach { addView(it) }
      }
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(R.string.new_theme_title)
        .setView(container)
        .setPositiveButton(R.string.new_theme_create, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
      // Set here so that a mistake leaves the dialog open.
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        try {
          val id =
            SoundThemes.create(
              context,
              name.text.toString(),
              description.text.toString(),
              author.text.toString(),
              controlSounds.isChecked,
              values[audio3d.selectedItemPosition],
            )
          // The theme is in use while it is made, so that each sound is heard as it is set.
          SoundThemes.activate(context, prefs, id)
          dialog.dismiss()
          SoundThemeActions.open(this, ThemeSoundsFragment::class.java)
        } catch (e: IllegalArgumentException) {
          name.error = getString(R.string.new_theme_needs_name)
          name.requestFocus()
        } catch (e: ThemeExistsException) {
          name.error = getString(R.string.new_theme_exists)
          name.requestFocus()
        }
      }
    }
    dialog.show()
    name.requestFocus()
  }

  private fun askForLink() {
    val context = requireContext()
    val field =
      KeyboardSafeEditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setHint(R.string.sound_theme_link_hint)
        isSingleLine = true
      }
    // Lines the field up with the dialog's message.
    val padding = (24 * context.resources.displayMetrics.density).toInt()
    val container =
      FrameLayout(context).apply {
        setPadding(padding, 0, padding, 0)
        addView(field)
      }
    AlertDialog.Builder(context)
      .setTitle(R.string.title_pref_sound_theme_install_link)
      .setMessage(R.string.sound_theme_link_message)
      .setView(container)
      .setPositiveButton(R.string.sound_theme_install) { _, _ ->
        val link = field.text.toString().trim()
        if (link.isNotEmpty()) {
          startActivity(SoundThemeInstallActivity.linkIntent(context, link))
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
    field.requestFocus()
  }

  private fun launchPicker() {
    try {
      installFile.launch(PACK_TYPES)
    } catch (e: ActivityNotFoundException) {
      // Watches have no document picker.
      actions.showMessage(getString(R.string.sound_theme_no_picker))
    }
  }

  private fun category(context: Context, title: CharSequence) =
    PreferenceCategory(context).apply {
      this.title = title
      isIconSpaceReserved = false
    }

  private fun action(
    context: Context,
    title: CharSequence,
    summary: CharSequence?,
    onClick: () -> Unit,
  ): Preference =
    AccessibilitySuitePreference(context).apply {
      this.title = title
      this.summary = summary
      isPersistent = false
      isIconSpaceReserved = false
      setOnPreferenceClickListener {
        onClick()
        true
      }
    }

  /** A row for a theme, with Export and Remove as accessibility actions. */
  private class ThemeRow(
    context: Context,
    private val theme: SoundTheme,
    title: CharSequence,
    summary: CharSequence?,
    private val export: () -> Unit,
    private val remove: () -> Unit,
  ) : AccessibilitySuitePreference(context) {
    init {
      key = "pref_sound_theme_${theme.id}"
      this.title = title
      this.summary = summary
      isPersistent = false
      isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      // Rows are reused for other themes, so every bind replaces the actions.
      val exportLabel = context.getString(R.string.sound_theme_export)
      ViewCompat.replaceAccessibilityAction(
        holder.itemView,
        AccessibilityActionCompat(ACTION_EXPORT, exportLabel),
        exportLabel,
        AccessibilityViewCommand { _, _ ->
          export()
          true
        },
      )
      if (!theme.isBuiltIn) {
        val removeLabel = context.getString(R.string.sound_theme_remove)
        ViewCompat.replaceAccessibilityAction(
          holder.itemView,
          AccessibilityActionCompat(ACTION_REMOVE, removeLabel),
          removeLabel,
          AccessibilityViewCommand { _, _ ->
            remove()
            true
          },
        )
      } else {
        ViewCompat.removeAccessibilityAction(holder.itemView, ACTION_REMOVE)
      }
    }
  }

  companion object {
    private val ACTION_EXPORT = R.id.accessibility_custom_action_0
    private val ACTION_REMOVE = R.id.accessibility_custom_action_1

    // File managers do not all call ZIP files by the same type.
    @JvmField
    val PACK_TYPES =
      arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

    /** The name to show for [theme]: Backtalk's own is translated. */
    @JvmStatic
    fun nameOf(context: Context, theme: SoundTheme): String =
      if (theme.isBuiltIn) context.getString(R.string.sound_theme_backtalk)
      else theme.manifest.name

    @JvmStatic
    fun summaryOf(context: Context, theme: SoundTheme, inUse: Boolean): String {
      val parts = ArrayList<String>()
      if (inUse) parts += context.getString(R.string.sound_theme_in_use)
      if (theme.isBuiltIn) {
        parts += context.getString(R.string.sound_theme_backtalk_description)
      } else {
        theme.manifest.author?.let { parts += context.getString(R.string.sound_theme_by, it) }
        theme.manifest.description?.let { parts += it }
      }
      return parts.joinToString("\n")
    }

    /** Describes a theme for the person choosing or installing it. */
    @JvmStatic
    fun describe(context: Context, manifest: SoundThemeManifest, soundCount: Int): String {
      val lines = ArrayList<String>()
      manifest.author?.let { lines += context.getString(R.string.sound_theme_by, it) }
      manifest.description?.let { lines += it }
      val vibrations = manifest.vibrations.size
      lines +=
        context.resources.getQuantityString(R.plurals.sound_theme_sounds, soundCount, soundCount) +
          " " +
          context.resources.getQuantityString(R.plurals.sound_theme_vibrations, vibrations, vibrations)
      manifest.controlSounds?.let {
        lines +=
          context.getString(
            if (it) R.string.sound_theme_control_sounds_on else R.string.sound_theme_control_sounds_off
          )
      }
      manifest.license?.let { lines += context.getString(R.string.sound_theme_license, it) }
      manifest.website?.let { lines += it }
      return lines.joinToString("\n\n")
    }
  }
}
