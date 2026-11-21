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

package com.google.android.accessibility.talkback.selector

import android.os.Handler
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Picks the reading control for the item a touch ended on, a moment after the touch.
 *
 * Picking it asks the app about the item. Right after a key is typed the app is busy, so asking at
 * once holds up the typed letter.
 */
internal class LateSettingSwitch(
  private val handler: Handler,
  private val switchSetting: SwitchSetting,
) {
  fun interface SwitchSetting {
    fun switchFor(node: AccessibilityNodeInfo)
  }

  private var node: AccessibilityNodeInfo? = null
  private val run = Runnable { runNow() }

  /** Keeps the item that a touch focused, to pick its control when the touch ends. */
  fun hold(node: AccessibilityNodeInfo) {
    pause()
    this.node = node
  }

  /** Picks the control for the item held, a moment after a touch ends. */
  fun schedule() {
    pause()
    if (node != null) {
      handler.postDelayed(run, DELAY_MS)
    }
  }

  /** Waits for the next touch to end, keeping the item in case that touch focuses nothing. */
  fun pause() {
    handler.removeCallbacks(run)
  }

  /** Forgets the item, because a newer focus picks the control. */
  fun cancel() {
    pause()
    node = null
  }

  /** Picks the control now if one is waiting, so a reading control uses the right one. */
  fun runNow() {
    pause()
    val waiting = node ?: return
    node = null
    switchSetting.switchFor(waiting)
  }

  companion object {
    const val DELAY_MS = 200L
  }
}
