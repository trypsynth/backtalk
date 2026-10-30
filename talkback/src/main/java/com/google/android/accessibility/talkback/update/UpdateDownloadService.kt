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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.Phase
import com.google.android.accessibility.talkback.update.UpdateDownloadManager.State
import com.google.android.libraries.accessibility.utils.log.LogUtils

/** Owns the foreground lifetime of APK downloading and preparation on phones and watches. */
class UpdateDownloadService : Service() {
  private lateinit var manager: UpdateDownloadManager
  private var wakeLock: PowerManager.WakeLock? = null
  private val listener: () -> Unit = { showState() }

  override fun onCreate() {
    super.onCreate()
    manager = UpdateDownloadManager.get(this)
    createChannel(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      manager.cancel()
      if (!manager.state.cancellable) stopSelf()
      return START_NOT_STICKY
    }
    try {
      ServiceCompat.startForeground(
        this, NOTIFICATION_ID, notification(this, manager.state),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } catch (e: RuntimeException) {
      LogUtils.e("Updater", "Cannot enter foreground for update: %s", e)
      manager.fail(R.string.update_download_interrupted)
      showResult(this)
      stopSelf()
      return START_NOT_STICKY
    }
    manager.addListener(listener)
    if (wakeLock == null) {
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:update").apply {
          acquire(30 * 60 * 1000L)
        }
    }
    manager.runPending()
    showState()
    return START_NOT_STICKY
  }

  private fun showState() {
    if (manager.state.cancellable) {
      getSystemService(NotificationManager::class.java)
        .notify(NOTIFICATION_ID, notification(this, manager.state))
    } else {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
      showResult(this)
      stopSelf()
    }
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    manager.fail(R.string.update_download_interrupted)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    manager.removeListener(listener)
    if (manager.state.cancellable) {
      manager.fail(R.string.update_download_interrupted)
      showResult(this)
    }
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL_ID = "update_download"
    private const val NOTIFICATION_ID = 0x0BAC7A1D
    private const val RESULT_ID = 0x0BAC7A1E
    private const val ACTION_CANCEL = "com.google.android.accessibility.talkback.CANCEL_UPDATE"

    private fun createChannel(context: Context) {
      context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, context.getString(R.string.update_downloading), NotificationManager.IMPORTANCE_LOW)
      )
    }

    internal fun text(context: Context, state: State): String = context.getString(when (state.phase) {
      Phase.CONNECTING -> R.string.update_connecting
      Phase.DOWNLOADING -> R.string.update_downloading
      Phase.PREPARING -> R.string.update_preparing
      Phase.INSTALLING -> R.string.update_installing
      Phase.CONFIRM -> R.string.update_confirm_install
      Phase.DONE -> R.string.update_installed
      Phase.CANCELED -> R.string.update_install_canceled
      else -> state.message
    })

    internal fun sizes(context: Context, state: State): String {
      val bytes = Formatter.formatShortFileSize(context, state.bytes)
      return if (state.total > 0) context.getString(
        R.string.update_download_progress, state.percent, bytes,
        Formatter.formatShortFileSize(context, state.total),
      ) else bytes
    }

    private fun notification(context: Context, state: State): Notification {
      val open = UpdateDownloadActivity.intent(context, UpdateDownloadManager.get(context).url)
      state.confirmation?.let { open.putExtra(UpdateDownloadActivity.EXTRA_CONFIRMATION, it) }
      val content = PendingIntent.getActivity(
        context, RESULT_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.icon)
        .setContentTitle(text(context, state))
        .setContentIntent(content)
        .setOnlyAlertOnce(true)
        .setOngoing(state.cancellable)
        .setAutoCancel(!state.cancellable)
      if (state.cancellable) {
        builder.setProgress(100, state.percent, state.phase != Phase.DOWNLOADING || state.total <= 0)
        if (state.phase == Phase.DOWNLOADING) builder.setContentText(sizes(context, state))
        val cancel = PendingIntent.getService(
          context, 0, Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL),
          PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, context.getString(android.R.string.cancel), cancel)
      }
      return builder.build()
    }

    internal fun showResult(context: Context) {
      createChannel(context)
      val state = UpdateDownloadManager.get(context).state
      val notifications = context.getSystemService(NotificationManager::class.java)
      if (state.phase == Phase.CANCELED || state.phase == Phase.DONE) {
        notifications.cancel(RESULT_ID)
      } else {
        notifications.notify(RESULT_ID, notification(context, state))
      }
    }

    internal fun clearResult(context: Context) {
      context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
    }
  }
}
