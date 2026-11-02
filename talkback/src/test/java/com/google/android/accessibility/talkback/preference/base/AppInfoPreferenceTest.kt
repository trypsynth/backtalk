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

import android.app.Application
import android.content.ContextWrapper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppInfoPreferenceTest {
  @Test fun opensSystemDetailsForTheOfficialInstallation() {
    assertTarget("fyi.quin.backtalk")
  }

  @Test fun aSeparateTestCopyNeverOpensTheOfficialAppsDetails() {
    assertTarget("fyi.quin.backtalk.updatetest")
  }

  private fun assertTarget(targetPackage: String) {
    val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
      override fun getPackageName(): String = targetPackage
    }
    val intent = AppInfoPreference.intent(context)
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:$targetPackage", intent.dataString)
  }
}
