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

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import com.google.android.accessibility.material.preference.AccessibilitySuiteListPreference
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.material.preference.AccessibilitySuiteSwitchPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.controlsounds.ControlSounds
import com.google.android.accessibility.talkback.controlsounds.ControlSoundsSettings
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * The settings of one sound theme, whether or not it is the one in use: control sounds, 3D audio,
 * its sounds, and using, describing, saving or removing it.
 */
class SoundThemeSettingsFragment : TalkbackBaseFragment() {
  private lateinit var prefs: SharedPreferences
  private var themeId = ""

  private val actions = SoundThemeActions(this, { prefs }, { leave() })

  public override fun getTitle(): CharSequence {
    val context = requireContext()
    val theme = SoundThemes.theme(context, arguments?.getString(ARG_THEME_ID).orEmpty())
    return theme?.let { SoundThemesFragment.nameOf(context, it) }
      ?: getText(R.string.title_pref_sound_themes)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    val context = requireContext()
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    themeId = arguments?.getString(ARG_THEME_ID).orEmpty()
    actions.restore(savedInstanceState)
    // Like every settings screen, so that nothing is ever saved outside Backtalk's settings.
    preferenceManager.setStorageDeviceProtected()
    preferenceScreen = preferenceManager.createPreferenceScreen(context)
  }

  override fun onResume() {
    super.onResume()
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

  private fun leave() {
    activity?.onBackPressedDispatcher?.onBackPressed()
  }

  private fun rebuild() {
    val context = requireContext()
    val screen = preferenceScreen
    screen.removeAll()
    val theme = SoundThemes.theme(context, themeId)
    if (theme == null) {
      // Removed, perhaps from another app.
      leave()
      return
    }
    val inUse = theme.id == SoundThemes.activeId(prefs)

    var controlSoundsOn = controlSoundsOf(context, theme, inUse)
    var audio3d = audio3dOf(theme, inUse)
    val switch =
      AccessibilitySuiteSwitchPreference(context).apply {
        key = "pref_theme_control_sounds_$themeId"
        isPersistent = false
        isChecked = controlSoundsOn
        setTitle(R.string.title_pref_control_sounds)
        setSummary(R.string.summary_pref_control_sounds)
        isIconSpaceReserved = false
      }
    val list =
      AccessibilitySuiteListPreference(context).apply {
        key = "pref_theme_audio_3d_$themeId"
        isPersistent = false
        setTitle(R.string.title_pref_control_sounds_3d)
        setDialogTitle(R.string.title_pref_control_sounds_3d)
        setEntries(R.array.pref_control_sounds_3d_entries)
        setEntryValues(R.array.pref_control_sounds_3d_values)
        value = audio3d
        summary = "%s"
        isEnabled = controlSoundsOn
        isIconSpaceReserved = false
      }
    switch.setOnPreferenceChangeListener { _, value ->
      controlSoundsOn = value as Boolean
      list.isEnabled = controlSoundsOn
      SoundThemes.setSettings(context, prefs, themeId, controlSoundsOn, audio3d)
      true
    }
    list.setOnPreferenceChangeListener { _, value ->
      audio3d = value as String
      SoundThemes.setSettings(context, prefs, themeId, controlSoundsOn, audio3d)
      true
    }
    screen.addPreference(switch)
    screen.addPreference(list)

    screen.addPreference(
      action(context, getString(R.string.sound_theme_customisation), getString(R.string.summary_pref_theme_sounds)) {
        // The sounds screen changes the theme in use.
        if (!inUse) SoundThemes.activate(context, prefs, themeId)
        SoundThemeActions.open(this, ThemeSoundsFragment::class.java)
      }
    )
    screen.addPreference(
      action(context, getString(R.string.sound_theme_about), null) { showAbout(theme) }
    )
    screen.addPreference(
      action(context, getString(R.string.sound_theme_export), null) { actions.exportTheme(theme) }
    )
    if (!theme.isBuiltIn) {
      screen.addPreference(
        action(context, getString(R.string.sound_theme_remove), null) {
          actions.confirmRemove(theme)
        }
      )
    }
  }

  /** The control sounds setting of [theme]: the live one for the theme in use. */
  private fun controlSoundsOf(context: Context, theme: SoundTheme, inUse: Boolean): Boolean =
    if (inUse) ControlSoundsSettings.isOn(prefs)
    else
      theme.manifest.controlSounds
        ?: (SoundThemes.soundFiles(theme).keys + theme.manifest.vibrations.keys).any {
          it in ControlSounds.SOUNDS
        }

  private fun audio3dOf(theme: SoundTheme, inUse: Boolean): String =
    (if (inUse) prefs.getString(ControlSoundsSettings.PREF_3D, null) else theme.manifest.audio3d)
      ?: ControlSoundsSettings.VALUE_3D_WITH_HEADPHONES

  private fun showAbout(theme: SoundTheme) {
    actions.showMessage(
      SoundThemesFragment.describe(
        requireContext(),
        theme.manifest,
        SoundThemes.soundFiles(theme).size,
      )
    )
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

  companion object {
    /** The id of the theme to show. */
    const val ARG_THEME_ID = "theme_id"
  }
}
