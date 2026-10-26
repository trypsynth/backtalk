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

package com.google.android.accessibility.talkback.compositor

import android.os.SystemClock

/**
 * Remembers the clicks whose sound and vibration were given as soon as their event arrived. The
 * event is handled about 150 ms later, once the clicked item has updated, to say its new state, and
 * must not give them a second time.
 *
 * Clicks are told apart by their event time, which the copy of the event handled later keeps. A
 * click whose event never reaches the compositor is forgotten after a while.
 */
class EarlyClickCues(clock: () -> Long = SystemClock::uptimeMillis) {
  private val given = RecentItems<Long>(MAX_CLICKS, MAX_AGE_MS, clock)

  /** Called when the cues for the click sent at [eventTime] have been given. */
  fun given(eventTime: Long) = given.add(eventTime)

  /** Whether the cues for the click sent at [eventTime] were given already. Forgets that click. */
  fun take(eventTime: Long): Boolean = given.remove(eventTime)

  private companion object {
    const val MAX_CLICKS = 8
    const val MAX_AGE_MS = 2_000L
  }
}
