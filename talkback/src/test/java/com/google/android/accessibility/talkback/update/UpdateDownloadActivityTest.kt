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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.Phase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloadActivityTest {
  private lateinit var context: Context
  private lateinit var manager: UpdateDownloadManager

  @Before fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = UpdateDownloadManager.get(context)
    manager.cancel()
    manager.installStatus(Phase.IDLE)
  }

  @After fun tearDown() { manager.cancel() }

  private fun views(view: View): List<View> = listOf(view) +
    if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

  private fun button(text: String): Button = views(ShadowDialog.getLatestDialog().window!!.decorView)
    .filterIsInstance<Button>().first { it.text.toString() == text && it.visibility == View.VISIBLE }

  @Test fun installEntryOpensAnActivityDirectly() {
    Updater.install(context, "https://example.com/wear.apk")
    val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertEquals(UpdateDownloadActivity::class.java.name, started.component!!.className)
  }

  @Test fun cancelButtonCancelsAnActiveDownload() {
    manager.start("https://example.com/wear.apk")
    val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java).setup()
    button(context.getString(android.R.string.cancel)).performClick()
    assertEquals(Phase.CANCELED, manager.state.phase)
    controller.pause().stop().destroy()
  }

  @Test fun leavingTheDialogKeepsTheDownloadActive() {
    manager.start("https://example.com/wear.apk")
    val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java).setup()
    controller.pause().stop().destroy()
    assertEquals(Phase.CONNECTING, manager.state.phase)
  }

  @Test fun permissionDenialOffersRetryAndGrantContinuesTheRequestedUpdate() {
    shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
    val controller = Robolectric.buildActivity(
      UpdateDownloadActivity::class.java,
      UpdateDownloadActivity.intent(context, "https://example.com/wear.apk", start = true),
    ).setup()
    val activity = controller.get()
    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
    controller.pause().resume()
    assertEquals(Phase.IDLE, manager.state.phase)
    button(context.getString(R.string.update_retry))
    shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
    button(context.getString(R.string.update_retry)).performClick()
    assertEquals(Phase.CONNECTING, manager.state.phase)
    controller.pause().stop().destroy()
  }

  @Test fun failedInstallationKeepsAVisibleErrorAndRetry() {
    manager.installStatus(Phase.FAILED)
    val controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java).setup()
    val text = views(ShadowDialog.getLatestDialog().window!!.decorView).filterIsInstance<TextView>()
    assertTrue(text.any { it.text.toString() == context.getString(R.string.update_install_failed) })
    button(context.getString(R.string.update_retry))
    controller.pause().stop().destroy()
  }

  @Test fun leavingTheSystemPromptKeepsInstallAvailableAfterReopeningProgress() {
    val confirmation = Intent("test.confirm.install")
    manager.installStatus(Phase.CONFIRM, confirmation)
    var controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java).setup()
    button(context.getString(R.string.update_dialog_install)).performClick()
    assertEquals(confirmation.action, shadowOf(controller.get()).nextStartedActivity.action)
    assertEquals(Phase.CONFIRM, manager.state.phase)
    controller.pause().stop().destroy()
    controller = Robolectric.buildActivity(UpdateDownloadActivity::class.java).setup()
    button(context.getString(R.string.update_dialog_install)).performClick()
    assertEquals(confirmation.action, shadowOf(controller.get()).nextStartedActivity.action)
    assertEquals(Phase.CONFIRM, manager.state.phase)
    controller.pause().stop().destroy()
  }

  @Test fun installStatusOpensTheSystemPromptWithoutAProgressActivityOrNotifications() {
    val application = RuntimeEnvironment.getApplication()
    org.robolectric.Shadows.shadowOf(
      context.getSystemService(android.app.NotificationManager::class.java)
    ).setNotificationsEnabled(false)
    val confirmation = Intent("test.confirm.background")
    Updater.onInstallStatus(context, Intent(UpdateReceiver.ACTION_INSTALL_STATUS)
      .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
      .putExtra(Intent.EXTRA_INTENT, confirmation))
    assertEquals(confirmation.action, shadowOf(application).nextStartedActivity.action)
    assertEquals(Phase.CONFIRM, manager.state.phase)
    assertSame(confirmation, manager.state.confirmation)
  }
}
