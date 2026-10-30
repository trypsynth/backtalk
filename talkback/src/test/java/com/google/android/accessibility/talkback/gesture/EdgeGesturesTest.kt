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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGesturesTest {
  private val events = mutableListOf<String>()
  private val menu =
    EdgeMenu(
      object : EdgeMenu.Listener {
        override fun onItemFocused(action: String) {
          events += "focus $action"
        }

        override fun onCancelled() {
          events += "cancel"
        }

        override fun onItemChosen(action: String) {
          events += "choose $action"
        }
      }
    )

  @Test
  fun edgeAt_findsBothEdges() {
    assertEquals(Edge.LEFT, EdgeGestureSettings.edgeAt(0f, WIDTH, EDGE))
    assertEquals(Edge.LEFT, EdgeGestureSettings.edgeAt(EDGE - 1, WIDTH, EDGE))
    assertNull(EdgeGestureSettings.edgeAt(EDGE, WIDTH, EDGE))
    assertNull(EdgeGestureSettings.edgeAt(WIDTH / 2, WIDTH, EDGE))
    assertEquals(Edge.RIGHT, EdgeGestureSettings.edgeAt(WIDTH - EDGE, WIDTH, EDGE))
    assertEquals(Edge.RIGHT, EdgeGestureSettings.edgeAt(WIDTH - 1, WIDTH, EDGE))
  }

  @Test
  fun layout_fewItemsUseTheFullStep() {
    val layout = EdgeMenuLayout(Edge.LEFT, WIDTH, EDGE, REST, START, STEP, 3)
    assertEquals(STEP, layout.step, 0f)
    assertEquals(EdgeMenuLayout.NONE, layout.itemAt(FIRST - 1))
    assertEquals(0, layout.itemAt(FIRST))
    assertEquals(1, layout.itemAt(FIRST + STEP))
    assertEquals(2, layout.itemAt(FIRST + 2 * STEP))
    // Past the last item, the finger stays on it.
    assertEquals(2, layout.itemAt(WIDTH - 1))
  }

  @Test
  fun layout_firstItemComesSoonOnAWideEdge() {
    // The first item is still on the edge, as the edge is wider than the distance to it.
    val layout = EdgeMenuLayout(Edge.LEFT, WIDTH, 4 * START, REST, START, STEP, 3)
    assertEquals(0, layout.itemAt(FIRST))
    assertTrue(FIRST < 4 * START)
  }

  @Test
  fun layout_manyItemsShareTheScreen() {
    val layout = EdgeMenuLayout(Edge.LEFT, WIDTH, EDGE, REST, START, STEP, 10)
    assertEquals((WIDTH - EDGE - FIRST) / 10, layout.step, 0.001f)
    assertTrue(layout.step < STEP)
    assertEquals(9, layout.itemAt(WIDTH - EDGE - 1))
  }

  @Test
  fun layout_rightEdgeCountsTowardTheLeft() {
    val layout = EdgeMenuLayout(Edge.RIGHT, WIDTH, EDGE, WIDTH - REST, START, STEP, 3)
    assertEquals(EdgeMenuLayout.NONE, layout.itemAt(WIDTH - FIRST + 1))
    assertEquals(0, layout.itemAt(WIDTH - FIRST))
    assertEquals(1, layout.itemAt(WIDTH - FIRST - STEP))
  }

  @Test
  fun menu_staysClosedBeforeTheFirstItem() {
    assertFalse(menu.open(Edge.LEFT, FIRST - 1, ACTIONS, WIDTH, EDGE, REST, START, STEP))
    assertFalse(menu.isOpen)
    assertTrue(events.isEmpty())
  }

  @Test
  fun menu_staysClosedWithoutItems() {
    assertFalse(menu.open(Edge.LEFT, FIRST + 10, emptyList(), WIDTH, EDGE, REST, START, STEP))
  }

  @Test
  fun menu_saysEachItemAndPerformsTheLastOne() {
    assertTrue(menu.open(Edge.LEFT, FIRST + 1, ACTIONS, WIDTH, EDGE, REST, START, STEP))
    menu.move(FIRST + 2)
    menu.move(FIRST + STEP + 1)
    menu.move(FIRST + 2 * STEP + 1)
    menu.move(FIRST + STEP + 1)
    menu.lift()
    assertEquals(listOf("focus a", "focus b", "focus c", "focus b", "choose b"), events)
    assertFalse(menu.isOpen)
  }

  @Test
  fun menu_backBeforeTheFirstItemCancels() {
    menu.open(Edge.LEFT, FIRST + 1, ACTIONS, WIDTH, EDGE, REST, START, STEP)
    menu.move(FIRST - 1)
    menu.lift()
    assertEquals(listOf("focus a", "cancel"), events)
  }

  @Test
  fun menu_secondFingerCancelsTheRestOfTheTouch() {
    menu.open(Edge.LEFT, FIRST + 1, ACTIONS, WIDTH, EDGE, REST, START, STEP)
    menu.abandon()
    menu.move(FIRST + STEP + 1)
    menu.lift()
    assertEquals(listOf("focus a", "cancel"), events)
  }

  @Test
  fun moved_movesAnItemUpOrDown() {
    assertEquals(listOf("b", "a", "c"), EdgeGestureSettings.moved(ACTIONS, 1, -1))
    assertEquals(listOf("a", "c", "b"), EdgeGestureSettings.moved(ACTIONS, 1, 1))
  }

  @Test
  fun moved_leavesItemsAtTheEndsWhereTheyAre() {
    assertEquals(ACTIONS, EdgeGestureSettings.moved(ACTIONS, 0, -1))
    assertEquals(ACTIONS, EdgeGestureSettings.moved(ACTIONS, 2, 1))
    assertEquals(ACTIONS, EdgeGestureSettings.moved(ACTIONS, 3, -1))
  }

  @Test
  fun settingsScreens_useTheKeysThatAreRead() {
    for (edge in Edge.entries) {
      val xml = File("src/main/res/xml/edge_gestures_${edge.prefName}_preferences.xml").readText()
      for (gesture in EdgeGesture.entries) {
        val key = EdgeGestureSettings.gestureKey(edge, gesture)
        assertTrue("$key missing", xml.contains("android:key=\"$key\""))
      }
      // The settings screen lists the menu items itself, after the gestures in this category.
      val gestures = "pref_category_edge_${edge.prefName}_gestures"
      assertTrue("$gestures missing", xml.contains("android:key=\"$gestures\""))
    }
    val gestures = File("src/main/res/xml/gesture_preferences.xml").readText()
    assertTrue(gestures.contains("android:key=\"${EdgeGestureSettings.PREF_ENABLED}\""))
    assertTrue(gestures.contains("android:key=\"${EdgeGestureSettings.PREF_WIDTH}\""))
  }

  private companion object {
    const val WIDTH = 1000f
    const val EDGE = 50f
    const val STEP = 100f
    // Where the finger rested, and how far it slides from there to the first item.
    const val REST = 10f
    const val START = 30f
    const val FIRST = REST + START
    val ACTIONS = listOf("a", "b", "c")
  }
}
