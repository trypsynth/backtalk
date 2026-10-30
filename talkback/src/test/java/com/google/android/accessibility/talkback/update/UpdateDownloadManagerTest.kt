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

package com.google.android.accessibility.talkback.update

import android.content.Context
import android.content.Intent
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.Phase
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.State
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadManagerTest {
  private lateinit var manager: UpdateDownloadManager
  private lateinit var context: Context

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("update_download", Context.MODE_PRIVATE).edit().clear().commit()
    manager = UpdateDownloadManager(context)
  }

  @Test fun repeatedStartKeepsTheOriginalTransfer() {
    manager.start("https://example.com/wear.apk")
    manager.start("https://example.com/phone.apk")
    assertEquals("https://example.com/wear.apk", manager.url)
    assertEquals(Phase.CONNECTING, manager.state.phase)
    manager.cancel()
  }

  @Test fun cancelIsTerminalAndRetryStartsFresh() {
    manager.start("https://example.com/wear.apk")
    manager.cancel()
    assertEquals(Phase.CANCELED, manager.state.phase)
    manager.start("https://example.com/retry.apk")
    assertEquals(Phase.CONNECTING, manager.state.phase)
    assertEquals(0L, manager.state.bytes)
    assertEquals("https://example.com/retry.apk", manager.url)
    manager.cancel()
  }

  @Test fun processRecreationReportsInterruptionAndRetainsRetryUrl() {
    manager.start("https://example.com/wear.apk")
    val restored = UpdateDownloadManager(context)
    assertEquals(Phase.FAILED, restored.state.phase)
    assertEquals("https://example.com/wear.apk", restored.url)
    manager.cancel()
  }

  @Test fun confirmationAndCommittedInstallationCannotBeCanceledAsDownloads() {
    val confirmation = Intent("confirm")
    manager.installStatus(Phase.CONFIRM, confirmation)
    manager.cancel()
    assertEquals(Phase.CONFIRM, manager.state.phase)
    assertSame(confirmation, manager.state.confirmation)
    manager.installStatus(Phase.INSTALLING)
    manager.cancel()
    assertEquals(Phase.INSTALLING, manager.state.phase)
  }

  @Test fun progressIsBoundedAndUnknownSizeIsIndeterminate() {
    assertEquals(50, State(Phase.DOWNLOADING, 50, 100).percent)
    assertEquals(100, State(Phase.DOWNLOADING, 200, 100).percent)
    assertEquals(0, State(Phase.DOWNLOADING, 100, -1).percent)
    assertEquals(0, State(Phase.DOWNLOADING, 100, 0).percent)
    assertEquals(50, State(Phase.DOWNLOADING, Long.MAX_VALUE / 2, Long.MAX_VALUE).percent)
  }
}
