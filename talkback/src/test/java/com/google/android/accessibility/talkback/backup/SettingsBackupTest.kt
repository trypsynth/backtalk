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

import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
  private val main = FakeSharedPreferences()
  private val braille = FakeSharedPreferences()
  private val stores =
    mapOf(
      SettingsBackup.STORE_MAIN to main,
      SettingsBackup.STORE_BRAILLE_KEYBOARD to braille,
    )

  @Test
  fun roundTripKeepsEveryType() {
    main
      .edit()
      .putBoolean("b", true)
      .putInt("i", 7)
      .putLong("l", 1L shl 40)
      .putFloat("f", 0.8f)
      .putString("s", "text")
      .putStringSet("set", setOf("x", "y"))
      .apply()
    braille.edit().putString("grade", "2").apply()

    val json = SettingsBackup.export(stores)
    val otherMain = FakeSharedPreferences()
    val otherBraille = FakeSharedPreferences()
    assertRestored(
      SettingsBackup.import(
        mapOf(
          SettingsBackup.STORE_MAIN to otherMain,
          SettingsBackup.STORE_BRAILLE_KEYBOARD to otherBraille,
        ),
        json,
      )
    )

    assertEquals(main.all, otherMain.all)
    assertEquals("2", otherBraille.getString("grade", null))
  }

  @Test
  fun exportLeavesOutWhatIsNotPortable() {
    main
      .edit()
      .putString("pref_gemini_api_key", "secret")
      .putBoolean("pref_image_description_installed", true)
      .putInt("pref_icon_detection_download_dialog_shown_times", 2)
      .putStringSet("pref_speech_control_allowed_apps", setOf("a.b"))
      .putString("pref_speech_rate", "1.5")
      .apply()

    val other = FakeSharedPreferences()
    assertRestored(SettingsBackup.import(mapOf(SettingsBackup.STORE_MAIN to other), SettingsBackup.export(stores)))

    assertEquals(setOf("pref_speech_rate"), other.all.keys)
  }

  @Test
  fun importRemovesPortableSettingsMissingFromTheFile() {
    val json = SettingsBackup.export(stores)
    main.edit().putBoolean("added_later", true).apply()

    assertRestored(SettingsBackup.import(stores, json))

    assertFalse(main.contains("added_later"))
  }

  @Test
  fun importNeverTouchesWhatIsNotPortable() {
    main.edit().putString("pref_gemini_api_key", "mine").apply()
    val json =
      """{"format":1,"stores":{"main":{"pref_gemini_api_key":{"t":"string","v":"theirs"}}}}"""

    assertRestored(SettingsBackup.import(stores, json))

    assertEquals("mine", main.getString("pref_gemini_api_key", null))
  }

  @Test
  fun importLeavesStoresMissingFromTheFileAlone() {
    braille.edit().putString("grade", "2").apply()
    val json = """{"format":1,"stores":{"main":{}}}"""

    assertRestored(SettingsBackup.import(stores, json))

    assertEquals("2", braille.getString("grade", null))
  }

  @Test
  fun importRejectsGarbageWithoutChangingAnything() {
    main.edit().putBoolean("b", true).apply()

    assertFailed(SettingsBackup.import(stores, "not json"))
    assertFailed(SettingsBackup.import(stores, """{"format":2,"stores":{}}"""))
    assertFailed(SettingsBackup.import(stores, """{"format":1}"""))
    // The second store is bad, so the first must not have been applied either.
    assertFailed(
      SettingsBackup.import(
        stores,
        """{"format":1,"stores":{"main":{"b":{"t":"bool","v":false}},"braille_keyboard":{"x":{"t":"nope","v":1}}}}""",
      )
    )

    assertTrue(main.getBoolean("b", false))
    assertNull(braille.getString("x", null))
  }

  @Test
  fun importSaysWhenASettingNeedsARestart() {
    main.edit().putBoolean(GESTURES, true).apply()
    val on = SettingsBackup.export(stores)
    main.edit().putBoolean(GESTURES, false).apply()

    assertEquals(SettingsBackup.ImportResult.RESTORED_NEEDS_RESTART, SettingsBackup.import(stores, on))
    assertTrue(main.getBoolean(GESTURES, false))
    // Nothing changed this time.
    assertEquals(SettingsBackup.ImportResult.RESTORED, SettingsBackup.import(stores, on))
  }

  @Test
  fun anUnsetRestartSettingCountsAsItsDefault() {
    val unset = SettingsBackup.export(stores)
    main.edit().putBoolean(GESTURES, true).apply()

    // The default is true, so an explicit true is no change.
    assertEquals(SettingsBackup.ImportResult.RESTORED, SettingsBackup.import(stores, unset))
  }

  @Test
  fun portableKeys() {
    assertTrue(SettingsBackup.isPortable("pref_speech_rate"))
    assertTrue(SettingsBackup.isPortable("pref_direct_touch_apps"))
    assertFalse(SettingsBackup.isPortable("pref_gemini_api_key"))
    assertFalse(SettingsBackup.isPortable("some_other_api_key"))
    assertFalse(SettingsBackup.isPortable("update_last_check_time"))
    assertFalse(SettingsBackup.isPortable("remembered_devices_with_addresses"))
  }

  private fun assertRestored(result: SettingsBackup.ImportResult) =
    assertTrue(result != SettingsBackup.ImportResult.FAILED)

  private fun assertFailed(result: SettingsBackup.ImportResult) =
    assertEquals(SettingsBackup.ImportResult.FAILED, result)

  private companion object {
    const val GESTURES = "pref_talkback_gesture_detection_key"
  }
}
