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

package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Lets the app answer once, off the main thread, before focus is restored when the screen turns on
 * (#152).
 *
 * When the screen turns on, the app in front is busy drawing again, and the first request Backtalk
 * sends it waits until that's done. Restoring focus sent that request from the main thread, which
 * then stopped for 100 to 300 ms on a watch. Now the last focused item is fetched on a background
 * thread first, and the restore runs once the app has answered, when its requests are quick. If the
 * app takes longer than [TIMEOUT_MS], the restore runs anyway, as it did before.
 */
object WakeFocusWarmUp {
  private const val TAG = "WakeFocusWarmUp"

  /** How long to wait for the app before restoring focus anyway. */
  @VisibleForTesting const val TIMEOUT_MS = 500L

  private val main by lazy { Handler(Looper.getMainLooper()) }

  @VisibleForTesting
  var executor: Executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, TAG).apply { isDaemon = true }
    }

  // Counts requests, so that only the latest one restores focus. Main thread only.
  private var latest = 0

  /**
   * Runs [restore] on the main thread once the app of [node] has answered a request for it, or
   * after [TIMEOUT_MS]. Call on the main thread. A later call, or [cancel], drops the restore.
   */
  @JvmStatic
  fun restoreAfterAppAnswers(node: AccessibilityNodeInfoCompat, restore: Runnable) {
    val request = ++latest
    val copy = AccessibilityNodeInfoCompat.obtain(node)
    val once =
      object : Runnable {
        var ran = false

        override fun run() {
          main.removeCallbacks(this)
          if (ran || request != latest) {
            return
          }
          ran = true
          restore.run()
        }
      }
    try {
      executor.execute {
        try {
          copy.refresh()
        } catch (e: RuntimeException) {
          LogUtils.w(TAG, "Could not fetch the last focused item: %s", e)
        }
        main.post(once)
      }
    } catch (e: RejectedExecutionException) {
      once.run()
      return
    }
    main.postDelayed(once, TIMEOUT_MS)
  }

  /** Drops a restore that is waiting for its app, as when the screen changes again. */
  @JvmStatic
  fun cancel() {
    latest++
  }
}
