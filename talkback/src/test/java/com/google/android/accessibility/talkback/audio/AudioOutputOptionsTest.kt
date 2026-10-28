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

package com.google.android.accessibility.talkback.audio

import android.content.Context
import android.media.AudioDeviceInfo
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.Companion.getOutputOptions
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.Companion.indexOfOption
import com.google.android.accessibility.talkback.audio.AudioDeviceRouter.OutputOption
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputOptionsTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private val options =
    listOf(
      OutputOption("System default", "default"),
      OutputOption("Phone speaker", "speaker"),
      OutputOption("Buds", "device:${AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}:Buds"),
      OutputOption("Headphones", "device:${AudioDeviceInfo.TYPE_WIRED_HEADPHONES}:Headphones"),
    )

  @Test
  fun options_withNoDevices_areDefaultThenSpeaker() {
    assertEquals(listOf("default", "speaker"), getOutputOptions(context).map { it.prefValue })
  }

  @Test
  fun index_ofEachKindOfValue() {
    assertEquals(0, indexOfOption(options, "default"))
    assertEquals(0, indexOfOption(options, null))
    assertEquals(1, indexOfOption(options, "speaker"))
    assertEquals(3, indexOfOption(options, options[3].prefValue))
  }

  @Test
  fun index_ofADeviceWhoseTypeChanged_matchesByName() {
    assertEquals(2, indexOfOption(options, "device:${AudioDeviceInfo.TYPE_BLE_HEADSET}:Buds"))
  }

  @Test
  fun index_ofAnyExternalDevice_isTheFirstDevice() {
    assertEquals(2, indexOfOption(options, "bluetooth_or_wired"))
  }

  @Test
  fun index_ofADisconnectedDevice_isMissing() {
    assertEquals(-1, indexOfOption(options, "device:${AudioDeviceInfo.TYPE_USB_HEADSET}:Dock"))
  }
}
