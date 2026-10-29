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

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import androidx.annotation.MainThread
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.TimeUnit

/**
 * Brings up Wi-Fi on a watch for the download of an update.
 *
 * A watch near its phone sends its traffic through the phone over Bluetooth, which is slow for a
 * whole APK. Asking Android for Wi-Fi turns the watch's radio on and joins a saved network in
 * range. The watch keeps Wi-Fi on until the request is released, so it is released as soon as the
 * download ends, and after [MAX_HOLD_MS] in any case.
 *
 * All of this runs on the thread of the handler, the main thread, one download at a time. The
 * download only gets the [Network], for its own connection: binding the whole process to Wi-Fi
 * would also move the screen reader's other connections, which would fail when Wi-Fi goes.
 */
internal class DownloadNetwork(
  private val connectivity: ConnectivityManager,
  private val handler: Handler,
) {
  /** The request that keeps Wi-Fi on, from [acquire] until [release]. */
  private var callback: ConnectivityManager.NetworkCallback? = null

  /** The download that waits to hear which network to use. */
  private var onReady: ((Network?) -> Unit)? = null

  private val waitTimeout = Runnable { useDefaultNetwork("no Wi-Fi in time") }

  private val holdTimeout = Runnable {
    LogUtils.w(TAG, "Released Wi-Fi at the time limit for a download")
    release()
  }

  /**
   * Asks for Wi-Fi, then calls [ready] once: with the Wi-Fi network, or with null to download over
   * the default network when Wi-Fi doesn't come up within [MAX_WAIT_MS]. Wi-Fi stays on until
   * [release].
   */
  @MainThread
  fun acquire(ready: (Network?) -> Unit) {
    release()
    onReady = ready
    val request =
      NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val requestCallback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          if (callback === this) {
            deliver(network)
          }
        }

        override fun onUnavailable() {
          // Android can say at once that it has no Wi-Fi to offer. It has then released the
          // request itself, so it isn't released again.
          if (callback === this) {
            callback = null
            useDefaultNetwork("Android has no Wi-Fi to offer")
          }
        }
      }
    callback = requestCallback
    try {
      connectivity.requestNetwork(request, requestCallback, handler)
    } catch (e: RuntimeException) {
      callback = null
      useDefaultNetwork(e.toString())
      return
    }
    handler.postDelayed(waitTimeout, MAX_WAIT_MS)
    handler.postDelayed(holdTimeout, MAX_HOLD_MS)
  }

  /**
   * Stops waiting for Wi-Fi, so that the download starts over the default network now. A download
   * that already has Wi-Fi keeps it.
   */
  @MainThread
  fun stopWaiting() {
    if (onReady != null) {
      useDefaultNetwork("stopped waiting")
    }
  }

  /** Lets the watch turn Wi-Fi off again, unless something else needs it. Can be called again. */
  @MainThread
  fun release() {
    handler.removeCallbacks(waitTimeout)
    handler.removeCallbacks(holdTimeout)
    val held = callback ?: return
    callback = null
    try {
      connectivity.unregisterNetworkCallback(held)
    } catch (e: IllegalArgumentException) {
      LogUtils.w(TAG, "Wi-Fi was already released: %s", e)
    }
  }

  private fun useDefaultNetwork(reason: String) {
    LogUtils.w(TAG, "Downloading the update without Wi-Fi: %s", reason)
    release()
    deliver(null)
  }

  private fun deliver(network: Network?) {
    handler.removeCallbacks(waitTimeout)
    val ready = onReady ?: return
    onReady = null
    if (network != null) {
      LogUtils.i(TAG, "Downloading the update over Wi-Fi")
    }
    ready(network)
  }

  private companion object {
    const val TAG = "DownloadNetwork"

    /** How long to wait for Wi-Fi before downloading over the default network. */
    val MAX_WAIT_MS = TimeUnit.SECONDS.toMillis(20)

    /** How long one download can keep Wi-Fi on. */
    val MAX_HOLD_MS = TimeUnit.MINUTES.toMillis(10)
  }
}
