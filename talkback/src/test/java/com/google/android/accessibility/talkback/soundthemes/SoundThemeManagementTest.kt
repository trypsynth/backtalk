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
import com.google.android.accessibility.talkback.controlsounds.ControlSoundsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundThemeManagementTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs: SharedPreferences = context.getSharedPreferences("themes-test", 0)

  @Before
  fun clean() {
    prefs.edit().clear().commit()
    SoundThemes.installed(context).filter { !it.isBuiltIn }.forEach {
      it.directory.deleteRecursively()
    }
  }

  @Test
  fun createWritesTheManifest() {
    val id = SoundThemes.create(context, " Calm ", "Soft", " ", true, "always")
    val theme = SoundThemes.theme(context, id)!!
    assertEquals("Calm", theme.manifest.name)
    assertEquals("Soft", theme.manifest.description)
    assertNull(theme.manifest.author)
    assertEquals(true, theme.manifest.controlSounds)
    assertEquals("always", theme.manifest.audio3d)
  }

  @Test
  fun createRefusesADuplicate() {
    SoundThemes.create(context, "Calm", "", "", false, "headphones")
    assertThrows(ThemeExistsException::class.java) {
      SoundThemes.create(context, "calm", "", "", false, "headphones")
    }
  }

  @Test
  fun createRefusesABlankName() {
    assertThrows(IllegalArgumentException::class.java) {
      SoundThemes.create(context, "  ", "", "", false, "headphones")
    }
  }

  @Test
  fun settingsOfAThemeNotInUseLeavePreferencesAlone() {
    val id = SoundThemes.create(context, "Calm", "", "", false, "headphones")
    SoundThemes.setSettings(context, prefs, id, true, "never")
    assertEquals("never", SoundThemes.theme(context, id)!!.manifest.audio3d)
    assertFalse(prefs.contains(ControlSoundsSettings.PREF_ON))
  }

  @Test
  fun settingsOfTheThemeInUseReachPreferences() {
    val id = SoundThemes.create(context, "Calm", "", "", false, "headphones")
    SoundThemes.activate(context, prefs, id)
    SoundThemes.setSettings(context, prefs, id, true, "never")
    assertTrue(ControlSoundsSettings.isOn(prefs))
    assertEquals("never", prefs.getString(ControlSoundsSettings.PREF_3D, null))
  }
}
