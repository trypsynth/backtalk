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

package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Watches for another accessibility service that explores by touch, such as a second screen reader
 * or a screen reader's helper service.
 *
 * Android keeps one gesture detection mode for each display, shared by every service. When Backtalk
 * recognizes gestures itself and another service handles touches too, double tap and double-tap
 * and hold can stop working, which leaves the phone unusable (#242). While another service explores
 * by touch, Backtalk leaves gestures to Android, as TalkBack does.
 *
 * [onChanged] runs on the main thread when [otherServiceExploresByTouch] changes. It runs at once
 * when another service starts exploring by touch, and shortly after the last one stops, because
 * Android sets up touch handling again after that and would drop gesture detection set up sooner.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OtherTouchExplorers(context: Context, private val onChanged: Runnable) {
  private val manager = context.getSystemService(AccessibilityManager::class.java)
  private val packageName = context.packageName
  private val executor = context.mainExecutor
  private val handler = Handler(Looper.getMainLooper())
  private val listener =
    AccessibilityManager.AccessibilityServicesStateChangeListener { update() }
  private val settle = Runnable { update(settled = true) }

  /** Whether another enabled service explores by touch. */
  var otherServiceExploresByTouch = false
    private set

  /** Reads the enabled services and starts watching them. */
  fun start() {
    otherServiceExploresByTouch = check()
    manager?.addAccessibilityServicesStateChangeListener(executor, listener)
  }

  fun stop() {
    manager?.removeAccessibilityServicesStateChangeListener(listener)
    handler.removeCallbacks(settle)
  }

  private fun update(settled: Boolean = false) {
    val now = check()
    if (now == otherServiceExploresByTouch) {
      handler.removeCallbacks(settle)
      return
    }
    if (!now && !settled) {
      handler.removeCallbacks(settle)
      handler.postDelayed(settle, SETTLE_DELAY_MS)
      return
    }
    handler.removeCallbacks(settle)
    otherServiceExploresByTouch = now
    LogUtils.i(TAG, "Another service explores by touch: %b", now)
    onChanged.run()
  }

  private fun check(): Boolean =
    try {
      manager
        ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.any { isOtherTouchExplorer(it.id, it.flags, packageName) } ?: false
    } catch (e: RuntimeException) {
      LogUtils.e(TAG, "Cannot read the enabled services: %s", e)
      false
    }

  companion object {
    private const val TAG = "OtherTouchExplorers"

    /** How long after the last other service stops exploring by touch to report it. */
    private const val SETTLE_DELAY_MS = 500L

    /**
     * Returns whether the service with [serviceId] and [flags] explores by touch and isn't from
     * [packageName].
     */
    @VisibleForTesting
    @JvmStatic
    fun isOtherTouchExplorer(serviceId: String?, flags: Int, packageName: String): Boolean =
      (flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0 &&
        serviceId?.let { ComponentName.unflattenFromString(it)?.packageName } != packageName
  }
}
