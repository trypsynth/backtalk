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
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.Phase
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** A visible, accessible host for download progress and the system's install confirmation. */
class UpdateDownloadActivity : AppCompatActivity() {
  private lateinit var manager: UpdateDownloadManager
  private lateinit var dialog: A11yAlertDialogWrapper
  private lateinit var stage: TextView
  private lateinit var sizes: TextView
  private lateinit var progress: ProgressBar
  private lateinit var action: Button
  private lateinit var close: Button
  private var resumed = false
  private var startRequested = false
  private var waitingPermission = false
  private var permissionDenied = false
  private val listener: () -> Unit = { render() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    manager = UpdateDownloadManager.get(this)
    startRequested = savedInstanceState?.getBoolean("startRequested") ?: intent.getBooleanExtra(EXTRA_START, false)
    waitingPermission = savedInstanceState?.getBoolean("waitingPermission") ?: false
    permissionDenied = savedInstanceState?.getBoolean("permissionDenied") ?: false
    buildDialog()
    acceptConfirmation(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    startRequested = intent.getBooleanExtra(EXTRA_START, false)
    acceptConfirmation(intent)
    if (resumed) resumeUpdate()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("startRequested", startRequested)
    outState.putBoolean("waitingPermission", waitingPermission)
    outState.putBoolean("permissionDenied", permissionDenied)
    super.onSaveInstanceState(outState)
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    manager.addListener(listener)
    resumeUpdate()
  }

  override fun onPause() {
    resumed = false
    manager.removeListener(listener)
    super.onPause()
  }

  override fun onDestroy() {
    dialog.setOnDismissListener(null)
    dialog.dismiss()
    super.onDestroy()
  }

  private fun acceptConfirmation(source: Intent) {
    val confirmation = IntentCompat.getParcelableExtra(source, EXTRA_CONFIRMATION, Intent::class.java)
    source.removeExtra(EXTRA_CONFIRMATION)
    if (confirmation != null) manager.installStatus(Phase.CONFIRM, confirmation)
  }

  private fun resumeUpdate() {
    if (waitingPermission) {
      waitingPermission = false
      permissionDenied = !packageManager.canRequestPackageInstalls()
      if (!permissionDenied) startRequested = true
    }
    if (startRequested) {
      startRequested = false
      if (!manager.state.active) begin()
    }
    render()
  }

  private fun begin() {
    val url = intent.getStringExtra(UpdateReceiver.EXTRA_DOWNLOAD_URL) ?: manager.url ?: return
    if (!packageManager.canRequestPackageInstalls()) {
      permissionDenied = true
      try {
        waitingPermission = true
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
      } catch (e: RuntimeException) {
        waitingPermission = false
        LogUtils.w("Updater", "Cannot open install apps setting: %s", e)
      }
      render()
      return
    }
    permissionDenied = false
    UpdateDownloadService.clearResult(this)
    manager.start(url)
  }

  private fun buildDialog() {
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val padding = (16 * resources.displayMetrics.density).toInt()
      setPadding(padding, padding, padding, padding)
    }
    stage = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    sizes = TextView(this)
    progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    action = Button(this).apply { setOnClickListener {
      if (manager.state.phase == Phase.CONFIRM) confirmInstall() else begin()
    } }
    close = Button(this).apply { setOnClickListener {
      if (manager.state.cancellable) manager.cancel()
      if (!manager.state.cancellable) finish()
    } }
    listOf(stage, sizes, progress, action, close).forEach { column.addView(it) }
    dialog = A11yAlertDialogWrapper.materialDialogBuilder(this)
      .setTitle(R.string.update_downloading)
      .setView(ScrollView(this).apply { addView(column) })
      .setOnCancelListener { finish() }
      .create()
    dialog.setOnDismissListener { if (!isChangingConfigurations) finish() }
    dialog.show()
    dialog.setCanceledOnTouchOutside(false)
  }

  private fun render() {
    val state = manager.state
    val text = if (permissionDenied) getString(R.string.update_allow_install)
      else UpdateDownloadService.text(this, state)
    if (stage.text.toString() != text) stage.text = text
    val downloading = state.phase == Phase.DOWNLOADING
    sizes.visibility = if (downloading) View.VISIBLE else View.GONE
    if (downloading) sizes.text = UpdateDownloadService.sizes(this, state)
    progress.visibility = if (state.cancellable) View.VISIBLE else View.GONE
    progress.isIndeterminate = !downloading || state.total <= 0
    if (!progress.isIndeterminate) progress.progress = state.percent
    action.visibility = if (permissionDenied || state.phase in setOf(Phase.FAILED, Phase.CANCELED, Phase.CONFIRM))
      View.VISIBLE else View.GONE
    action.setText(if (state.phase == Phase.CONFIRM) R.string.update_dialog_install else R.string.update_retry)
    close.setText(if (state.cancellable) android.R.string.cancel else android.R.string.ok)
  }

  private fun confirmInstall() {
    val confirmation = manager.state.confirmation ?: return
    // Keep the confirmation available: Home can dismiss the installer without a status callback.
    UpdateDownloadService.clearResult(this)
    try {
      startActivity(confirmation)
    } catch (e: RuntimeException) {
      LogUtils.e("Updater", "Cannot open install confirmation: %s", e)
      manager.installStatus(Phase.FAILED)
      UpdateDownloadService.showResult(this)
    }
  }

  companion object {
    internal const val EXTRA_CONFIRMATION = "install_confirmation"
    private const val EXTRA_START = "start_download"

    internal fun intent(context: Context, url: String?, start: Boolean = false): Intent =
      Intent(context, UpdateDownloadActivity::class.java)
        .putExtra(UpdateReceiver.EXTRA_DOWNLOAD_URL, url)
        .putExtra(EXTRA_START, start)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
