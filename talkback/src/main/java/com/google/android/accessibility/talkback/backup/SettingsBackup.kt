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
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Exports every setting to one JSON file, and restores them from it. Each preferences file is a
 * store in the file. A setting is whatever is in a store, apart from what [isPortable] rules out:
 * secrets, one-time state, and anything tied to this device, so that a new setting is covered
 * without being registered anywhere.
 */
object SettingsBackup {
  const val FILE_NAME = "backtalk_settings.json"

  const val STORE_MAIN = "main"
  // These are the names of the braille preferences files, which only the phone build can reach.
  const val STORE_BRAILLE_KEYBOARD = "braille_keyboard"
  const val STORE_BRAILLE_DISPLAY = "braille_display"

  private const val FORMAT = 1

  /** What an import did. */
  enum class ImportResult {
    /** The file is not a backup, and nothing changed. */
    FAILED,
    RESTORED,
    /** Restored, but a setting that is only read when Backtalk starts changed. */
    RESTORED_NEEDS_RESTART,
  }

  /**
   * Settings that only take effect when Backtalk is turned off and on, with the value they have
   * when nothing is stored. Add a setting here when the service reads it only as it starts.
   */
  private val RESTART_KEYS =
    mapOf(
      // "Handle gestures in Backtalk": the service registers gesture detection as it starts.
      "pref_talkback_gesture_detection_key" to true,
    )

  private val EXCLUDED_KEYS =
    setOf(
      GeminiApiKey.PREF_KEY,
      Updater.PREF_LAST_CHECK_TIME,
      Updater.PREF_LAST_NOTIFIED_BUILD,
      "pref_developer_version_code",
      "brailleime_shared_prefs_version",
      // Which apps may speak is consent given on this device, so it is asked for again.
      "pref_speech_control_allowed_apps",
      "pref_speech_control_denied_apps",
      // Paired displays are tied to this phone's Bluetooth and USB.
      "remembered_devices_with_addresses",
    )

  private val EXCLUDED_FRAGMENTS =
    listOf(
      "api_key",
      "_shown",
      "_installed",
      "_uninstalled",
      "install_lib",
      "first_update",
      "first_time",
      "opt_in_count",
    )

  /** Whether the setting stored under [key] goes into a backup, and is changed by a restore. */
  fun isPortable(key: String): Boolean =
    key !in EXCLUDED_KEYS && EXCLUDED_FRAGMENTS.none { key.contains(it) }

  /** The stores of [context], by their names in the file. */
  fun stores(context: Context): Map<String, SharedPreferences> =
    mapOf(
      STORE_MAIN to SharedPreferencesUtils.getSharedPreferences(context),
      STORE_BRAILLE_KEYBOARD to
        SharedPreferencesUtils.getSharedPreferences(context, STORE_BRAILLE_KEYBOARD),
      STORE_BRAILLE_DISPLAY to
        SharedPreferencesUtils.getSharedPreferences(context, STORE_BRAILLE_DISPLAY),
    )

  fun export(stores: Map<String, SharedPreferences>): String {
    val storesJson = JSONObject()
    for ((name, prefs) in stores) {
      val entries = JSONObject()
      for ((key, value) in prefs.all) {
        if (value != null && isPortable(key)) {
          entries.put(key, encode(value) ?: continue)
        }
      }
      storesJson.put(name, entries)
    }
    return JSONObject().put("format", FORMAT).put("stores", storesJson).toString()
  }

  /**
   * Replaces the settings in [stores] with those in [json], which [export] made. A setting that is
   * not in the file goes back to its default. Returns [ImportResult.FAILED] and changes nothing if
   * [json] is not a backup, and [ImportResult.RESTORED_NEEDS_RESTART] if a setting in [RESTART_KEYS]
   * changed. A store that is not in the file is left alone, and so is one the file has but [stores]
   * does not.
   */
  fun import(stores: Map<String, SharedPreferences>, json: String): ImportResult {
    val parsed: Map<String, Map<String, Any>>
    try {
      val backup = JSONObject(json)
      if (backup.getInt("format") != FORMAT) {
        return ImportResult.FAILED
      }
      val storesJson = backup.getJSONObject("stores")
      parsed =
        storesJson.keys().asSequence().associateWith { name ->
          val entries = storesJson.getJSONObject(name)
          entries.keys().asSequence().associateWith { decode(entries.getJSONObject(it)) }
        }
    } catch (_: JSONException) {
      return ImportResult.FAILED
    }
    var needsRestart = false
    for ((name, prefs) in stores) {
      val entries = parsed[name] ?: continue
      val before = prefs.all
      val editor = prefs.edit()
      prefs.all.keys.filter { isPortable(it) && it !in entries }.forEach { editor.remove(it) }
      for ((key, value) in entries) {
        if (isPortable(key)) {
          put(editor, key, value)
        }
      }
      editor.apply()
      needsRestart =
        needsRestart ||
          RESTART_KEYS.any { (key, default) -> (before[key] ?: default) != (entries[key] ?: default) }
    }
    return if (needsRestart) ImportResult.RESTORED_NEEDS_RESTART else ImportResult.RESTORED
  }

  private fun encode(value: Any): JSONObject? {
    val (type, json) =
      when (value) {
        is Boolean -> "bool" to value
        is Int -> "int" to value
        is Long -> "long" to value
        is Float -> "float" to value.toDouble()
        is String -> "string" to value
        is Set<*> -> "set" to JSONArray(value.filterIsInstance<String>().sorted())
        else -> return null
      }
    return JSONObject().put("t", type).put("v", json)
  }

  private fun decode(entry: JSONObject): Any =
    when (entry.getString("t")) {
      "bool" -> entry.getBoolean("v")
      "int" -> entry.getInt("v")
      "long" -> entry.getLong("v")
      "float" -> entry.getDouble("v").toFloat()
      "string" -> entry.getString("v")
      "set" -> {
        val array = entry.getJSONArray("v")
        (0 until array.length()).map { array.getString(it) }.toSet()
      }
      else -> throw JSONException("Unknown type ${entry.getString("t")}")
    }

  @Suppress("UNCHECKED_CAST")
  private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
    when (value) {
      is Boolean -> editor.putBoolean(key, value)
      is Int -> editor.putInt(key, value)
      is Long -> editor.putLong(key, value)
      is Float -> editor.putFloat(key, value)
      is String -> editor.putString(key, value)
      is Set<*> -> editor.putStringSet(key, value as Set<String>)
    }
  }
}
