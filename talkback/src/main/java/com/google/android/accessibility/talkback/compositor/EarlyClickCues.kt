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
 *
 * Also remembers the clicks whose new state was said, with their cues, before their event arrived,
 * so that the event gives no cues of its own.
 */
class EarlyClickCues(private val clock: () -> Long = SystemClock::uptimeMillis) {
  private val given = RecentItems<Long>(MAX_CLICKS, MAX_AGE_MS, clock)

  private class SaidEarly(val node: Any, val time: Long)

  private val saidEarly = RecentItems<SaidEarly>(MAX_CLICKS, MAX_AGE_MS, clock)

  /** Called when the cues for the click sent at [eventTime] have been given. */
  fun given(eventTime: Long) = given.add(eventTime)

  /** Whether the cues for the click sent at [eventTime] were given already. Forgets that click. */
  fun take(eventTime: Long): Boolean = given.remove(eventTime)

  /**
   * Called when the cues and new state of a click on [node] were given as soon as the app carried
   * it out. The app sent that click's event while carrying it out, so before now.
   */
  fun saidEarly(node: Any) = saidEarly.add(SaidEarly(node, clock()))

  /**
   * Whether the click on [node] sent at [eventTime] had its cues given by [saidEarly]. Forgets that
   * click. A later click on the same item, sent after its state was said, is not matched.
   */
  fun takeSaidEarly(node: Any, eventTime: Long): Boolean =
    saidEarly.removeFirst { it.node == node && eventTime <= it.time + CLOCK_SLACK_MS } != null

  private companion object {
    // Event times and the clock are both whole milliseconds of uptime.
    const val CLOCK_SLACK_MS = 1L
    // Generous, since a busy app can hold the event back well past its 150 ms delay, and an event
    // time never comes back, so remembering a click longer cannot skip the cues of another.
    const val MAX_CLICKS = 16
    const val MAX_AGE_MS = 10_000L
  }
}
