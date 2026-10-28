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
import com.google.android.accessibility.talkback.compositor.EarlyClickState.NodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EarlyClickStateTest {
  private val earlyClickState = EarlyClickState { _, _, _ -> }

  private val row = NodeState(false, false, null, expandable = false, collapsible = false)
  private val switchOff = NodeState(true, false, null, expandable = false, collapsible = false)
  private val switchOn = switchOff.copy(checked = true)

  @Suppress("DEPRECATION")
  private fun node(): AccessibilityNodeInfoCompat = AccessibilityNodeInfoCompat.obtain()

  @Test
  fun aSwitchHasAState() {
    val node = node().apply { isCheckable = true }
    assertEquals(listOf(switchOff), earlyClickState.before(node)?.items?.map { it.state })
  }

  @Test
  fun aPlainButtonHasNoState() {
    assertNull(earlyClickState.before(node()))
  }

  @Test
  fun anItemThatOpensHasAState() {
    val node = node().apply { addAction(AccessibilityActionCompat.ACTION_EXPAND) }
    assertTrue(NodeState.of(node).hasState)
    assertTrue(NodeState.of(node).expandable)
  }

  @Test
  fun theStateDescriptionIsPartOfTheState() {
    val node = node().apply {
      isCheckable = true
      stateDescription = "Half"
    }
    assertEquals("Half", NodeState.of(node).stateDescription)
    assertFalse(NodeState.of(node) == switchOff)
  }

  @Test
  fun aChildThatTurnedOnIsTheChange() {
    val item = EarlyClickState.Item(node(), row)
    val child = EarlyClickState.Item(node(), switchOff)
    // Empty nodes are all equal, so they are told apart by identity.
    val changed = earlyClickState.firstChange(listOf(item, child)) {
      if (it === child.node) switchOn else row
    }
    assertSame(child, changed)
  }

  @Test
  fun nothingChanged() {
    val item = EarlyClickState.Item(node(), switchOff)
    assertNull(earlyClickState.firstChange(listOf(item)) { switchOff })
  }

  @Test
  fun stopsAtTheFirstChange() {
    val first = EarlyClickState.Item(node(), switchOff)
    val second = EarlyClickState.Item(node(), switchOff)
    val read = mutableListOf<AccessibilityNodeInfoCompat>()
    val changed = earlyClickState.firstChange(listOf(first, second)) { read += it; switchOn }
    assertSame(first, changed)
    assertEquals(listOf(first.node), read)
  }

  @Test
  fun anItemThatIsGoneSaysNothing() {
    val item = EarlyClickState.Item(node(), switchOff)
    assertNull(earlyClickState.firstChange(listOf(item)) { null })
  }
}
