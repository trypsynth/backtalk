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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.File
import java.util.concurrent.Executors

/** Shared state for the update dialog, foreground service and install status receiver. */
internal class UpdateDownloadManager internal constructor(private val context: Context) {
  enum class Phase { IDLE, CONNECTING, DOWNLOADING, PREPARING, INSTALLING, CONFIRM, DONE, CANCELED, FAILED }

  data class State(
    val phase: Phase = Phase.IDLE,
    val bytes: Long = 0,
    val total: Long = -1,
    @StringRes val message: Int = R.string.update_download_interrupted,
    val confirmation: Intent? = null,
  ) {
    val active: Boolean
      get() = phase in setOf(Phase.CONNECTING, Phase.DOWNLOADING, Phase.PREPARING, Phase.INSTALLING, Phase.CONFIRM)
    val cancellable: Boolean
      get() = phase in setOf(Phase.CONNECTING, Phase.DOWNLOADING, Phase.PREPARING)
    val percent: Int
      get() = if (total > 0) (bytes.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0
  }

  private val prefs = context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
  private val handler = Handler(Looper.getMainLooper())
  private val executor = Executors.newSingleThreadExecutor()
  private val listeners = mutableSetOf<() -> Unit>()
  private var transfer: UpdateTransfer? = null
  private var running = false
  private var downloadNetwork: DownloadNetwork? = null
  var url: String? = prefs.getString("url", null)
    private set
  var state = restoredState()
    private set

  private fun restoredState(): State {
    if (prefs.getBoolean("active", false)) return State(Phase.FAILED)
    val phase = runCatching { Phase.valueOf(prefs.getString("phase", "IDLE")!!) }.getOrDefault(Phase.IDLE)
    return State(phase, message = prefs.getInt("message", R.string.update_download_interrupted))
  }

  fun addListener(listener: () -> Unit) { listeners.add(listener) }
  fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

  private fun publish(value: State) {
    val changedPhase = state.phase != value.phase
    state = value
    if (changedPhase) prefs.edit()
      .putBoolean("active", value.active)
      .putString("phase", value.phase.name)
      .putInt("message", value.message)
      .apply()
    listeners.toList().forEach { it() }
  }

  fun start(downloadUrl: String) {
    if (state.active) return
    url = downloadUrl
    prefs.edit().putString("url", downloadUrl).putBoolean("active", true).apply()
    transfer = UpdateTransfer()
    running = false
    publish(State(Phase.CONNECTING))
    try {
      ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
    } catch (e: RuntimeException) {
      LogUtils.e("Updater", "Cannot start update download service: %s", e)
      fail(R.string.update_download_failed)
    }
  }

  /** Called only after the service has entered the foreground. */
  fun runPending() {
    val task = transfer ?: return
    val source = url ?: return
    if (running || !state.cancellable) return
    running = true
    val wifi = if (FormFactorUtils.isAndroidWear()) {
      context.getSystemService(ConnectivityManager::class.java)?.let { DownloadNetwork(it, handler) }
    } else null
    downloadNetwork = wifi
    if (wifi == null) download(task, source, null, null)
    else wifi.acquire { network ->
      if (transfer === task && state.cancellable) download(task, source, network, wifi)
      else wifi.release()
    }
  }

  private fun download(task: UpdateTransfer, source: String, network: Network?, wifi: DownloadNetwork?) {
    executor.execute {
      val apk = File(context.cacheDir, "update/backtalk.apk")
      var downloaded = false
      try {
        if (!apk.parentFile!!.isDirectory && !apk.parentFile!!.mkdirs()) {
          throw java.io.IOException("Cannot create update directory")
        }
        var lastProgress = 0L
        task.download(context, source, apk, network) { bytes, total ->
          val now = SystemClock.elapsedRealtime()
          if (bytes == 0L || bytes == total || now - lastProgress >= 500) {
            lastProgress = now
            post(task, State(Phase.DOWNLOADING, bytes, total))
          }
        }
        downloaded = true
        handler.post { releaseNetwork(wifi) }
        post(task, State(Phase.PREPARING))
        Updater.installApk(context, apk, task)
        post(task, State(Phase.INSTALLING))
      } catch (e: Exception) {
        LogUtils.e("Updater", "Update transfer failed: %s", e)
        val message = if (e is Updater.WrongPackageException) R.string.update_wrong_package
          else if (network != null && !downloaded) R.string.update_wifi_download_failed
          else R.string.update_download_failed
        post(task, State(Phase.FAILED, message = message))
      } finally {
        apk.delete()
        handler.post { releaseNetwork(wifi) }
      }
    }
  }

  private fun releaseNetwork(wifi: DownloadNetwork? = downloadNetwork) {
    wifi?.release()
    if (downloadNetwork === wifi) downloadNetwork = null
  }

  private fun post(task: UpdateTransfer, value: State) {
    handler.post {
      // Ignore queued progress after Cancel, or after the installer has reported its status.
      if (transfer === task && state.cancellable) publish(value)
    }
  }

  fun cancel() {
    if (state.cancellable && transfer?.cancel() == true) {
      transfer = null
      releaseNetwork()
      publish(State(Phase.CANCELED))
    }
  }

  fun fail(@StringRes message: Int) {
    if (transfer?.cancel() == false) return
    transfer = null
    releaseNetwork()
    publish(State(Phase.FAILED, message = message))
  }

  fun installStatus(phase: Phase, confirmation: Intent? = null) {
    transfer = null
    publish(State(phase, message = R.string.update_install_failed, confirmation = confirmation))
  }

  companion object {
    private var instance: UpdateDownloadManager? = null
    fun get(context: Context): UpdateDownloadManager =
      instance ?: UpdateDownloadManager(context.applicationContext).also { instance = it }
  }
}
