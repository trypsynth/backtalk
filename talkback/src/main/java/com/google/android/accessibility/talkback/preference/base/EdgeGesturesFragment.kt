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

package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.Edge
import com.google.android.accessibility.talkback.gesture.EdgeGestureSettings
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity.TalkBackSubSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_ARGS
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME

/**
 * The actions of one edge: its swipes, its triple tap, and its menu items, which can be added,
 * moved, and removed.
 */
abstract class EdgeGesturesFragment(xmlResId: Int, private val edge: Edge) :
  TalkbackBaseFragment(xmlResId) {

  private val prefs
    get() = SharedPreferencesUtils.getSharedPreferences(requireContext())

  override fun onResume() {
    super.onResume()
    // The action of a gesture is chosen on another screen, so its summary may be out of date.
    val context = requireContext()
    val screen = preferenceScreen ?: return
    for (index in 0 until screen.preferenceCount) {
      val category = screen.getPreference(index) as? PreferenceGroup ?: continue
      for (child in 0 until category.preferenceCount) {
        val preference = category.getPreference(child) as? GestureListPreference ?: continue
        preference.summary =
          GestureShortcutMapping.getActionString(
            context,
            prefs.getString(preference.key, preference.defaultValue),
          )
      }
    }
    showMenu()
  }

  private fun menuActions(): List<String> =
    EdgeGestureSettings.menuActions(prefs, edge, getString(R.string.shortcut_value_unassigned))

  /** Lists the menu items after the edge's gestures, then the item that adds one. */
  private fun showMenu() {
    val category =
      findPreference<PreferenceGroup>("pref_category_edge_${edge.prefName}_gestures") ?: return
    val actions = menuActions()
    // An item's action may have been cleared on the screen of actions.
    EdgeGestureSettings.setMenuActions(prefs, edge, actions)
    val context = requireContext()
    // Only the menu's own rows go; the gestures stay.
    (category.preferenceCount - 1 downTo 0)
      .map { category.getPreference(it) }
      .filter { it is MenuItemPreference || it.key == ADD_KEY }
      .forEach { category.removePreference(it) }
    actions.forEachIndexed { index, action ->
      category.addPreference(
        MenuItemPreference(context, index, actions.size).apply {
          key = menuItemPreferenceKey(index)
          isPersistent = false
          title = GestureShortcutMapping.getActionString(context, action)
          setSummary(R.string.summary_pref_edge_menu_item)
        }
      )
    }
    if (actions.size < EdgeGestureSettings.MAX_MENU_ITEMS) {
      category.addPreference(
        Preference(context).apply {
          key = ADD_KEY
          isPersistent = false
          setTitle(R.string.title_pref_edge_menu_add)
          setOnPreferenceClickListener {
            chooseAction(actions.size, getString(R.string.title_pref_edge_menu_add))
            true
          }
        }
      )
    }
  }

  /** Lists the actions for the item at [index], which may be the next one to add. */
  private fun chooseAction(index: Int, title: CharSequence) {
    val preference =
      GestureListPreference(requireContext(), null).apply {
        key = EdgeGestureSettings.menuItemKey(edge, index + 1)
        this.title = title
      }
    showActionScreen(preference)
  }

  /** Lists what can be done with the item at [index]. */
  private fun showOptions(index: Int) {
    val actions = menuActions()
    val action = actions.getOrNull(index) ?: return
    val title = GestureShortcutMapping.getActionString(requireContext(), action)
    val options = mutableListOf<Pair<Int, () -> Unit>>()
    options += R.string.edge_menu_item_change to { chooseAction(index, title) }
    if (index > 0) {
      options += R.string.edge_menu_item_move_up to { move(index, -1) }
    }
    if (index < actions.size - 1) {
      options += R.string.edge_menu_item_move_down to { move(index, 1) }
    }
    options += R.string.edge_menu_item_remove to { remove(index) }
    A11yAlertDialogWrapper.materialDialogBuilder(requireContext(), parentFragmentManager)
      .setTitle(title)
      .setAdapter(
        ArrayAdapter(
          requireContext(),
          android.R.layout.simple_list_item_1,
          options.map { getString(it.first) },
        )
      ) { _, which ->
        options[which].second()
      }
      .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
      .create()
      .show()
  }

  private fun move(index: Int, by: Int) {
    EdgeGestureSettings.setMenuActions(
      prefs,
      edge,
      EdgeGestureSettings.moved(menuActions(), index, by),
    )
    showMenu()
    focusMenuItem(menuItemPreferenceKey(index + by))
  }

  private fun remove(index: Int) {
    EdgeGestureSettings.setMenuActions(
      prefs,
      edge,
      menuActions().filterIndexed { position, _ -> position != index },
    )
    showMenu()
    // The next item takes the removed one's place, or else the item that adds one.
    val next = menuItemPreferenceKey(index)
    focusMenuItem(if (findPreference<Preference>(next) != null) next else ADD_KEY)
  }

  /** Moves accessibility focus to the menu item with [key], once the list shows it. */
  private fun focusMenuItem(key: String) {
    val list = listView ?: return
    list.postDelayed(
      {
        val preference = findPreference<Preference>(key) ?: return@postDelayed
        val adapter = list.adapter as? PreferenceGroup.PreferencePositionCallback
        val position = adapter?.getPreferenceAdapterPosition(preference) ?: return@postDelayed
        val focus = AccessibilityActionCompat.ACTION_ACCESSIBILITY_FOCUS.id
        list
          .findViewHolderForAdapterPosition(position)
          ?.itemView
          ?.performAccessibilityAction(focus, null)
      },
      FOCUS_DELAY_MS,
    )
  }

  override fun onDisplayPreferenceDialog(preference: Preference) {
    if (preference !is GestureListPreference) {
      super.onDisplayPreferenceDialog(preference)
      return
    }
    // Null on phones and watches, which list the actions on a screen of their own.
    val fragment: Fragment? = preference.createDialogFragment()
    if (fragment is PreferenceDialogFragmentCompat) {
      @Suppress("DEPRECATION") fragment.setTargetFragment(this, 0)
      fragment.show(parentFragmentManager, preference.key)
    } else {
      showActionScreen(preference)
    }
  }

  /** Lists the actions for [preference] on a screen of their own, as gesture settings do. */
  private fun showActionScreen(preference: GestureListPreference) {
    startActivity(
      Intent(context, TalkBackSubSettings::class.java)
        .putExtra(FRAGMENT_NAME, GesturePreferenceFragmentCompat::class.java.canonicalName)
        .putExtra(
          FRAGMENT_ARGS,
          GesturePreferenceFragmentCompat.createBundleForFragmentArguments(preference),
        )
    )
  }

  /** A menu item. Tapping it lists its options, which are also its accessibility actions. */
  private inner class MenuItemPreference(context: Context, val index: Int, val count: Int) :
    Preference(context) {

    override fun onClick() {
      showOptions(index)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
      super.onBindViewHolder(holder)
      val view = holder.itemView
      // The view may have shown another item before, with other actions.
      setAction(view, R.id.edge_menu_item_move_up, R.string.edge_menu_item_move_up, index > 0) {
        move(index, -1)
      }
      setAction(
        view,
        R.id.edge_menu_item_move_down,
        R.string.edge_menu_item_move_down,
        index < count - 1,
      ) {
        move(index, 1)
      }
      setAction(view, R.id.edge_menu_item_remove, R.string.edge_menu_item_remove, true) {
        remove(index)
      }
    }

    private fun setAction(
      view: View,
      id: Int,
      label: Int,
      available: Boolean,
      perform: () -> Unit,
    ) {
      if (!available) {
        ViewCompat.removeAccessibilityAction(view, id)
        return
      }
      ViewCompat.replaceAccessibilityAction(
        view,
        AccessibilityActionCompat(id, null),
        getString(label),
      ) { _, _ ->
        perform()
        true
      }
    }
  }

  private companion object {
    const val ADD_KEY = "edge_menu_add"
    // Long enough for the list to show the menu as it now is.
    const val FOCUS_DELAY_MS = 200L

    fun menuItemPreferenceKey(index: Int) = "edge_menu_item_$index"
  }
}

class LeftEdgeGesturesFragment :
  EdgeGesturesFragment(R.xml.edge_gestures_left_preferences, Edge.LEFT) {
  public override fun getTitle(): CharSequence = getText(R.string.title_pref_edge_left)
}

class RightEdgeGesturesFragment :
  EdgeGesturesFragment(R.xml.edge_gestures_right_preferences, Edge.RIGHT) {
  public override fun getTitle(): CharSequence = getText(R.string.title_pref_edge_right)
}
