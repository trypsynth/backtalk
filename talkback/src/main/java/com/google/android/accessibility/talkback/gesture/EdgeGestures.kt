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

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min

/** A side of the screen that edge gestures start on. */
enum class Edge(internal val prefName: String) {
  LEFT("left"),
  RIGHT("right"),
}

/** A gesture that can have an action of its own when it starts on an edge. */
enum class EdgeGesture(internal val prefName: String) {
  SWIPE_UP("swipe_up"),
  SWIPE_DOWN("swipe_down"),
  TRIPLE_TAP("triple_tap"),
}

/**
 * The edge gesture settings. Each edge has actions for [EdgeGesture]s and a hidden menu of up to
 * [MAX_MENU_ITEMS] actions. A gesture without an action does what it does anywhere else.
 */
object EdgeGestureSettings {
  const val PREF_ENABLED = "pref_edge_gestures"
  const val MAX_MENU_ITEMS = 10

  // The width of each edge, chosen in quarters of a centimeter, from 0.5 to 2 cm.
  const val PREF_WIDTH = "pref_edge_width"
  const val MIN_WIDTH_QUARTERS = 2
  const val MAX_WIDTH_QUARTERS = 8
  const val DEFAULT_WIDTH_QUARTERS = 4

  // The furthest the finger moves from one menu item to the next.
  const val MAX_MENU_STEP_CM = 1f

  // How far the finger slides from where it rested to the first menu item.
  const val MENU_START_CM = 0.5f

  /** The width of each edge in centimeters. */
  @JvmStatic
  fun widthCm(prefs: SharedPreferences): Float = widthQuarters(prefs) / 4f

  /** The width of each edge in quarters of a centimeter. */
  @JvmStatic
  fun widthQuarters(prefs: SharedPreferences): Int =
    prefs
      .getInt(PREF_WIDTH, DEFAULT_WIDTH_QUARTERS)
      .coerceIn(MIN_WIDTH_QUARTERS, MAX_WIDTH_QUARTERS)

  /** The key of the action for [gesture] on [edge]. The settings screens use the same keys. */
  @JvmStatic
  fun gestureKey(edge: Edge, gesture: EdgeGesture): String =
    "pref_edge_${edge.prefName}_${gesture.prefName}"

  /** The key of the action of the menu item at [position], from 1, on [edge]. */
  @JvmStatic
  fun menuItemKey(edge: Edge, position: Int): String = "pref_edge_${edge.prefName}_menu_$position"

  @JvmStatic
  fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_ENABLED, false)

  /** Returns the action for [gesture] on [edge], or null if it has none. */
  @JvmStatic
  fun action(
    prefs: SharedPreferences,
    edge: Edge,
    gesture: EdgeGesture,
    unassigned: String,
  ): String? = assigned(prefs.getString(gestureKey(edge, gesture), null), unassigned)

  /** Returns the actions of the menu on [edge] in order, leaving out positions without one. */
  @JvmStatic
  fun menuActions(prefs: SharedPreferences, edge: Edge, unassigned: String): List<String> =
    (1..MAX_MENU_ITEMS).mapNotNull {
      assigned(prefs.getString(menuItemKey(edge, it), null), unassigned)
    }

  /**
   * Stores [actions], at most [MAX_MENU_ITEMS] of them, as the menu of [edge] in this order, from
   * position 1 with no gaps, and clears the positions after them.
   */
  @JvmStatic
  fun setMenuActions(prefs: SharedPreferences, edge: Edge, actions: List<String>) {
    val editor = prefs.edit()
    for (position in 1..MAX_MENU_ITEMS) {
      val action = actions.getOrNull(position - 1)
      if (action == null) {
        editor.remove(menuItemKey(edge, position))
      } else {
        editor.putString(menuItemKey(edge, position), action)
      }
    }
    editor.apply()
  }

  /** Returns [actions] with the item at [index] moved by [by] places, if it can move that far. */
  @JvmStatic
  fun moved(actions: List<String>, index: Int, by: Int): List<String> {
    val target = index + by
    if (index !in actions.indices || target !in actions.indices) {
      return actions
    }
    return actions.toMutableList().apply { add(target, removeAt(index)) }
  }

  /** Returns the edge that [x] is on, or null if it is on neither. */
  @JvmStatic
  fun edgeAt(x: Float, screenWidth: Float, edgeWidth: Float): Edge? =
    when {
      x < edgeWidth -> Edge.LEFT
      x >= screenWidth - edgeWidth -> Edge.RIGHT
      else -> null
    }

  private fun assigned(action: String?, unassigned: String): String? =
    action?.takeIf { it.isNotEmpty() && it != unassigned }
}

/**
 * Works out which item of an edge menu a finger is on. The first item starts [startDistance] inward
 * from [restX], where the finger rested, whatever the edge width. Each next item is one step
 * further, at most [maxStep], so that all of them fit before the other edge.
 */
class EdgeMenuLayout(
  private val edge: Edge,
  screenWidth: Float,
  edgeWidth: Float,
  private val restX: Float,
  private val startDistance: Float,
  maxStep: Float,
  private val itemCount: Int,
) {
  val step: Float =
    min(
      maxStep,
      max(1f, distancePastFirstItem(otherEdge(screenWidth, edgeWidth))) / max(1, itemCount),
    )

  /** Returns the index of the item at [x], or [NONE] while [x] is before the first item. */
  fun itemAt(x: Float): Int {
    val distance = distancePastFirstItem(x)
    if (distance < 0) {
      return NONE
    }
    return min((distance / step).toInt(), itemCount - 1)
  }

  private fun otherEdge(screenWidth: Float, edgeWidth: Float): Float =
    if (edge == Edge.LEFT) screenWidth - edgeWidth else edgeWidth

  private fun distancePastFirstItem(x: Float): Float =
    (if (edge == Edge.LEFT) x - restX else restX - x) - startDistance

  companion object {
    const val NONE = -1
  }
}

/**
 * The hidden menu of an edge. It opens when a finger that rested on the edge slides from there
 * toward the middle, says each item as the finger reaches it, and performs the item that the finger
 * lifts on. Lifting before the first item, or after a second finger has landed, performs nothing.
 *
 * Once open, the menu owns the rest of the touch: [EdgeMenuTouch.isOwned] tells touch exploration
 * to leave the items under the finger alone, and not to activate the last one when the finger
 * lifts.
 */
class EdgeMenu(private val listener: Listener) {

  /** Receives what the menu does. */
  interface Listener {
    /** The finger reached the item with [action]. */
    fun onItemFocused(action: String)

    /** The finger went back before the first item, so lifting it now performs nothing. */
    fun onCancelled()

    /** The finger lifted on the item with [action]. */
    fun onItemChosen(action: String)
  }

  private var layout: EdgeMenuLayout? = null
  private var actions: List<String> = emptyList()
  private var current = EdgeMenuLayout.NONE
  // A second finger landed, so the rest of the touch does nothing.
  private var abandoned = false

  /** Whether the menu is open and owns the current touch. */
  val isOpen: Boolean
    get() = layout != null

  /** Opens the menu of [edge] if the finger at [x] is on its first item. Returns whether it did. */
  fun open(
    edge: Edge,
    x: Float,
    actions: List<String>,
    screenWidth: Float,
    edgeWidth: Float,
    restX: Float,
    startDistance: Float,
    maxStep: Float,
  ): Boolean {
    if (actions.isEmpty()) {
      return false
    }
    val newLayout =
      EdgeMenuLayout(edge, screenWidth, edgeWidth, restX, startDistance, maxStep, actions.size)
    if (newLayout.itemAt(x) == EdgeMenuLayout.NONE) {
      return false
    }
    layout = newLayout
    this.actions = actions
    current = EdgeMenuLayout.NONE
    abandoned = false
    EdgeMenuTouch.isOwned = true
    move(x)
    return true
  }

  /** Follows the finger to [x]. */
  fun move(x: Float) {
    val menuLayout = layout ?: return
    if (abandoned) {
      return
    }
    val index = menuLayout.itemAt(x)
    if (index == current) {
      return
    }
    current = index
    if (index == EdgeMenuLayout.NONE) {
      listener.onCancelled()
    } else {
      listener.onItemFocused(actions[index])
    }
  }

  /** A second finger landed. Cancels the menu for the rest of the touch. */
  fun abandon() {
    if (layout == null || abandoned) {
      return
    }
    abandoned = true
    if (current != EdgeMenuLayout.NONE) {
      current = EdgeMenuLayout.NONE
      listener.onCancelled()
    }
  }

  /** The finger lifted. Performs the item it was on, if any, and closes the menu. */
  fun lift() {
    val chosen = if (abandoned) null else actions.getOrNull(current)
    clear()
    if (chosen != null) {
      listener.onItemChosen(chosen)
    }
  }

  /** Closes the menu without performing anything. */
  fun clear() {
    layout = null
    actions = emptyList()
    current = EdgeMenuLayout.NONE
    abandoned = false
  }
}

/**
 * Whether an edge menu owns the current touch, for touch exploration, which reads it on another
 * path than the touch itself. It stays set after the finger lifts, until the next touch starts, as
 * the end of touch exploration comes after the lift.
 */
object EdgeMenuTouch {
  @JvmStatic @Volatile var isOwned = false
}
