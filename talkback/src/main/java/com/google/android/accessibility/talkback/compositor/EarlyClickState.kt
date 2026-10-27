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
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
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

  /** The state of a clicked item, or null if a click says no state for it. */
  fun before(target: AccessibilityNodeInfoCompat): List<NodeState>? =
    read(target, refresh = false).map { it.second }.takeIf { states -> states.any { it.hasState } }

  /**
   * Called when the app has carried out the click on [target], whose state was [before]. Says the
   * new state if it changed, of the item itself or else of the child that changed.
   */
  fun after(target: AccessibilityNodeInfoCompat, before: List<NodeState>, eventId: EventId?) {
    @Suppress("DEPRECATION") // obtain(): copy, so that the caller's node is left as it was.
    val node = AccessibilityNodeInfoCompat.obtain(target)
    if (!node.refresh()) {
      return
    }
    val after = read(node, refresh = true)
    // A child is said itself, since the item's own copy of it is not read again.
    val changed = firstChange(before, after.map { it.second }) ?: return
    speaker.speak(node, after[changed].first, eventId)
  }

  /**
   * The place of the first item whose state changed from [before] to [after], or null if none did,
   * or if children came or went, as then the lists don't match.
   */
  internal fun firstChange(before: List<NodeState>, after: List<NodeState>): Int? =
    if (after.size != before.size) null else after.indices.firstOrNull { after[it] != before[it] }

  /**
   * The state of [node] and of its children, which the click rule says. A child is read again from
   * the app if [refresh] is set, since the saved copy is only updated by the app's later event.
   */
  private fun read(
    node: AccessibilityNodeInfoCompat,
    refresh: Boolean,
  ): List<Pair<AccessibilityNodeInfoCompat, NodeState>> {
    val states = ArrayList<Pair<AccessibilityNodeInfoCompat, NodeState>>(node.childCount + 1)
    states += node to NodeState.of(node)
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      // Only children with a state, so that a click on an item with many children reads few.
      if (!NodeState.of(child).hasState || (refresh && !child.refresh())) {
        continue
      }
      states += child to NodeState.of(child)
    }
    return states
  }

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
      fun of(node: AccessibilityNodeInfoCompat): NodeState {
        val actions = node.actionList
        return NodeState(
          checkable = node.isCheckable,
          checked = node.isChecked,
          stateDescription = node.stateDescription?.toString(),
          expandable = actions.any { it.id == AccessibilityActionCompat.ACTION_EXPAND.id },
          collapsible = actions.any { it.id == AccessibilityActionCompat.ACTION_COLLAPSE.id },
        )
      }
    }
  }
}
