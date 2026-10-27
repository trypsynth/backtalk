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

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EventId

/**
 * Says the new state of an item that Backtalk clicks, such as "On" for a switch, as soon as the app
 * has carried out the click, if the app changed the state while carrying it out, as most switches
 * and checkboxes do. Otherwise, as in apps that update the screen a frame later, the state is said
 * as before, from the app's click or state-change event, which comes about 100 ms later.
 *
 * Only items with a state that a click says are read again, since reading waits for the app.
 */
class EarlyClickState(private val speaker: Speaker) {

  /** Says the state of [changed], the clicked [target] or its child. */
  fun interface Speaker {
    fun speak(
      target: AccessibilityNodeInfoCompat,
      changed: AccessibilityNodeInfoCompat,
      eventId: EventId?,
    )
  }

  /**
   * The clicked item and those of its children that have a state, with that state, read before the
   * click.
   */
  class Before
  internal constructor(
    internal val nodes: List<AccessibilityNodeInfoCompat>,
    internal val states: List<NodeState>,
  )

  /** The state of a clicked item, or null if a click says no state for it. */
  fun before(target: AccessibilityNodeInfoCompat): Before? {
    val nodes = ArrayList<AccessibilityNodeInfoCompat>(target.childCount + 1)
    @Suppress("DEPRECATION") // obtain(): copy, so that refreshing it leaves the caller's node alone.
    nodes += AccessibilityNodeInfoCompat.obtain(target)
    val states = arrayListOf(NodeState.of(target))
    for (i in 0 until target.childCount) {
      val child = target.getChild(i) ?: continue
      val state = NodeState.of(child)
      // Only children with a state, so that a click on an item with many children reads few.
      if (state.hasState) {
        nodes += child
        states += state
      }
    }
    return if (states.any { it.hasState }) Before(nodes, states) else null
  }

  /**
   * Called when the app has carried out the click read in [before]. Reads the same items again from
   * the app, since the saved copies are only updated by the app's later events, and says the new
   * state if it changed, of the item itself or else of the child that changed.
   */
  fun after(before: Before, eventId: EventId?) {
    if (!before.nodes.all { it.refresh() }) {
      return
    }
    // A child is said itself, since the item's own copy of it is not read again.
    val changed = firstChange(before.states, before.nodes.map(NodeState::of)) ?: return
    speaker.speak(before.nodes[0], before.nodes[changed], eventId)
  }

  /** The place of the first item whose state changed from [before] to [after], or null. */
  internal fun firstChange(before: List<NodeState>, after: List<NodeState>): Int? =
    after.indices.firstOrNull { after[it] != before[it] }

  /** What a click says of one item: whether it is checked, its state, and whether it is open. */
  data class NodeState(
    val checkable: Boolean,
    val checked: Boolean,
    val stateDescription: String?,
    val expandable: Boolean,
    val collapsible: Boolean,
  ) {
    /** Whether a click says a state for the item. */
    val hasState: Boolean
      get() = checkable || expandable || collapsible

    companion object {
      fun of(node: AccessibilityNodeInfoCompat) =
        NodeState(
          checkable = node.isCheckable,
          checked = node.isChecked,
          stateDescription = AccessibilityNodeInfoUtils.getState(node)?.toString(),
          expandable = AccessibilityNodeInfoUtils.isExpandable(node),
          collapsible = AccessibilityNodeInfoUtils.isCollapsible(node),
        )
    }
  }
}
