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

import android.content.Context
import android.net.ConnectivityManager
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanReachModelTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Before
  fun goOffline() {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    shadowOf(connectivity).setActiveNetworkInfo(null)
  }

  @Test
  fun cloudNeedsTheNetwork() {
    assertFalse(GeminiFunctionUtils.canReachModel(context))
  }

  @Test
  fun onDeviceAiWorksOffline() {
    OnDeviceAiSettings.setOnDevice(SharedPreferencesUtils.getSharedPreferences(context), true)
    assertTrue(GeminiFunctionUtils.canReachModel(context))
  }
}
