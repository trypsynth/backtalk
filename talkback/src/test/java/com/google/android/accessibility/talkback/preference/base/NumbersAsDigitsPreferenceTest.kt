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

package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class NumbersAsDigitsPreferenceTest {
  @Test fun disabledByDefaultAndPersistsChanges() {
    val context = RuntimeEnvironment.getApplication()
    val prefs = context.getSharedPreferences("digits-test", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    assertFalse(read(prefs, context))
    val key = context.getString(R.string.pref_speak_numbers_as_digits_key)
    prefs.edit().putBoolean(key, true).commit()
    assertTrue(read(prefs, context))
    prefs.edit().putBoolean(key, false).commit()
    assertFalse(read(prefs, context))
  }

  @Test fun verbositySwitchUsesPersistentKeyAndDisabledDefault() {
    val context = RuntimeEnvironment.getApplication()
    context.resources.getXml(R.xml.verbosity_preferences).use { xml ->
      var found = 0
      while (xml.next() != XmlPullParser.END_DOCUMENT) {
        if (xml.eventType != XmlPullParser.START_TAG) continue
        val ns = "http://schemas.android.com/apk/res/android"
        if (xml.getAttributeResourceValue(ns, "key", 0) != R.string.pref_speak_numbers_as_digits_key) continue
        found++
        assertEquals(R.bool.pref_speak_numbers_as_digits_default,
          xml.getAttributeResourceValue(ns, "defaultValue", 0))
        assertTrue(xml.name.endsWith("AccessibilitySuiteSwitchPreference"))
      }
      assertEquals(1, found)
    }
  }

  private fun read(prefs: SharedPreferences, context: Context) =
    SharedPreferencesUtils.getBooleanPref(prefs, context.resources,
      R.string.pref_speak_numbers_as_digits_key, R.bool.pref_speak_numbers_as_digits_default)
}
