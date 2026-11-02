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

import android.content.Context
import android.content.res.XmlResourceParser
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.analytics.TalkBackAnalytics
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator
import com.google.android.accessibility.talkback.selector.SelectorController.Setting
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebReadingControlTest {
  @Test
  fun enabledPreservationKeepsSelectedControlAcrossWebFocusChanges() {
    val fixture = ControllerFixture(preserve = true)
    val controls =
        listOf(
            Setting.GRANULARITY_HEADINGS,
            Setting.GRANULARITY_LINKS,
            Setting.GRANULARITY_CONTROLS,
            Setting.GRANULARITY_CHARACTERS,
            Setting.GRANULARITY_WORDS,
            Setting.GRANULARITY_LINES,
            Setting.GRANULARITY_PARAGRAPHS,
        ) + Setting.values().filter { it.name.startsWith("GRANULARITY_WEB_") }
    controls.forEach { setting ->
      assertTrue(fixture.controller.selectSettingSilently(setting))
      fixture.focus(web = true, actions = true)
      fixture.assertSelected(setting)
      fixture.focus(web = true, actions = true)
      fixture.assertSelected(setting)
    }
  }

  @Test
  fun disabledPreservationSelectsActionsAndRestoresPreviousControl() {
    val fixture = ControllerFixture(preserve = false)
    assertTrue(fixture.controller.selectSettingSilently(Setting.GRANULARITY_HEADINGS))
    fixture.focus(web = true, actions = true)
    fixture.assertSelected(Setting.ACTIONS)
    fixture.focus(web = true, actions = false)
    fixture.assertSelected(Setting.GRANULARITY_HEADINGS)
  }

  @Test
  fun enabledPreservationAllowsExplicitActionsSelection() {
    val fixture = ControllerFixture(preserve = true)
    assertTrue(fixture.controller.selectSettingSilently(Setting.GRANULARITY_HEADINGS))
    fixture.focus(web = true, actions = true)
    fixture.assertSelected(Setting.GRANULARITY_HEADINGS)
    assertTrue(fixture.controller.selectSettingSilently(Setting.ACTIONS))
    fixture.assertSelected(Setting.ACTIONS)
    fixture.focus(web = true, actions = true)
    fixture.assertSelected(Setting.ACTIONS)
  }

  @Test
  fun unsupportedTableControlsAllowActionsAfterLeavingTable() {
    listOf(Setting.GRANULARITY_ROW_COLUMN, Setting.GRANULARITY_ROW, Setting.GRANULARITY_COLUMN)
        .forEach { setting ->
          val fixture = ControllerFixture(preserve = true)
          assertTrue(fixture.controller.selectSettingSilently(Setting.GRANULARITY_HEADINGS))
          fixture.focus(web = true, actions = false, table = true)
          fixture.assertSelected(Setting.GRANULARITY_ROW_COLUMN)
          // Seed the selection from the table we have just left.
          fixture.seedSelection(setting)
          fixture.focus(web = true, actions = true)
          fixture.assertSelected(Setting.ACTIONS)
        }
  }

  @Test
  fun unsupportedReadingControlsAllowAutomaticActions() {
    val fixture = ControllerFixture(preserve = true)
    listOf(
        Setting.GRANULARITY_DEFAULT,
        Setting.GRANULARITY_WINDOWS,
        Setting.GRANULARITY_CONTAINERS,
        Setting.GRANULARITY_SEARCH,
    ).forEach { setting ->
      fixture.seedSelection(setting)
      fixture.focus(web = true, actions = true)
      fixture.assertSelected(Setting.ACTIONS)
    }
  }

  @Test
  fun leavingUnsupportedContextRestoresCachedReadingControl() {
    val fixture = ControllerFixture(preserve = true)
    assertTrue(fixture.controller.selectSettingSilently(Setting.GRANULARITY_HEADINGS))
    fixture.focus(web = false, actions = true)
    fixture.assertSelected(Setting.ACTIONS)
    fixture.focus(web = true, actions = false)
    fixture.assertSelected(Setting.GRANULARITY_HEADINGS)

    // A table context can still take over and restore the previous control on exit.
    fixture.focus(web = true, actions = false, table = true)
    fixture.assertSelected(Setting.GRANULARITY_ROW_COLUMN)
    fixture.focus(web = true, actions = false)
    fixture.assertSelected(Setting.GRANULARITY_HEADINGS)
  }

  @Test
  fun selectedWebReadingControlsResistAutomaticActions() {
    listOf(
        Setting.GRANULARITY_HEADINGS,
        Setting.GRANULARITY_LINKS,
        Setting.GRANULARITY_CONTROLS,
        Setting.GRANULARITY_CHARACTERS,
        Setting.GRANULARITY_WORDS,
    ).forEach { setting ->
      assertTrue(SelectorController.shouldPreserveWebReadingControl(setting, Setting.ACTIONS, true, true))
    }
    Setting.values().filter { it.name.startsWith("GRANULARITY_WEB_") }.forEach { setting ->
      assertTrue(SelectorController.shouldPreserveWebReadingControl(setting, Setting.ACTIONS, true, true))
    }
  }

  @Test
  fun disabledPreferenceAllowsAutomaticActions() {
    assertFalse(
        SelectorController.shouldPreserveWebReadingControl(
            Setting.GRANULARITY_HEADINGS, Setting.ACTIONS, false, true))
  }

  @Test
  fun currentActionsIsNotTreatedAsAWebReadingControl() {
    assertFalse(
        SelectorController.shouldPreserveWebReadingControl(
            Setting.ACTIONS, Setting.ACTIONS, true, true))
  }

  @Test
  fun actionsRemainEligibleWithPreservationEnabled() {
    val context = RuntimeEnvironment.getApplication()
    FormFactorUtils.initialize(context)
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().putBoolean(context.getString(R.string.pref_selector_actions_key), true).apply()
    val nodeInfo = AccessibilityNodeInfo.obtain()
    nodeInfo.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    val node = AccessibilityNodeInfoCompat.wrap(nodeInfo)
    val menuCreator =
        object : NodeMenuRuleCreator(null, null, null, null) {
          override fun getNodeMenuByRule(
              rule: NodeMenuRuleCreator.MenuRules,
              context: Context,
              node: AccessibilityNodeInfoCompat,
              includeAncestors: Boolean,
          ): List<ContextMenuItem> {
            assertEquals(NodeMenuRuleCreator.MenuRules.RULE_CUSTOM_ACTION, rule)
            assertTrue(includeAncestors)
            return listOf(ContextMenuItem(context, 0, 1, 0, "Custom action"))
          }
        }
    val actions = ActionsSetting(menuCreator, null)

    listOf(false, true).forEach { preserve ->
      prefs
          .edit()
          .putBoolean(context.getString(R.string.pref_preserve_web_reading_control_key), preserve)
          .apply()
      assertTrue(actions.isNodeSupportSetting(context, node))
      assertFalse(actions.isNodeSupportSetting(context, null))
    }
    nodeInfo.recycle()
  }

  @Test
  fun nonWebAndOtherContextualTransitionsAreUnchanged() {
    assertFalse(
        SelectorController.shouldPreserveWebReadingControl(
            Setting.GRANULARITY_HEADINGS, Setting.ACTIONS, true, false))
    assertFalse(
        SelectorController.shouldPreserveWebReadingControl(
            Setting.GRANULARITY_HEADINGS, Setting.ADJUSTABLE_WIDGET, true, true))
    assertFalse(
        SelectorController.shouldPreserveWebReadingControl(
            Setting.GRANULARITY_DEFAULT, Setting.ACTIONS, true, true))
    listOf(
            Setting.GRANULARITY_WINDOWS,
            Setting.GRANULARITY_CONTAINERS,
            Setting.GRANULARITY_SEARCH,
            Setting.GRANULARITY_ROW_COLUMN,
            Setting.GRANULARITY_ROW,
            Setting.GRANULARITY_COLUMN,
        )
        .forEach { setting ->
          assertFalse(
              SelectorController.shouldPreserveWebReadingControl(setting, Setting.ACTIONS, true, true))
        }
  }

  @Test
  fun navigationPreferenceIsOffByDefaultAndHasAccessibleText() {
    val resources = RuntimeEnvironment.getApplication().resources
    assertFalse(resources.getBoolean(R.bool.pref_preserve_web_reading_control_default))
    assertTrue(resources.getString(R.string.title_pref_preserve_web_reading_control).isNotBlank())
    assertTrue(resources.getString(R.string.summary_pref_preserve_web_reading_control).isNotBlank())

    val parser: XmlResourceParser = resources.getXml(R.xml.navigation_preferences)
    try {
      while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG &&
            parser.getAttributeResourceValue(ANDROID_NS, "key", 0) ==
                R.string.pref_preserve_web_reading_control_key) {
          assertEquals(
              R.bool.pref_preserve_web_reading_control_default,
              parser.getAttributeResourceValue(ANDROID_NS, "defaultValue", 0))
          assertEquals(
              R.string.title_pref_preserve_web_reading_control,
              parser.getAttributeResourceValue(ANDROID_NS, "title", 0))
          assertEquals(
              R.string.summary_pref_preserve_web_reading_control,
              parser.getAttributeResourceValue(ANDROID_NS, "summary", 0))
          return
        }
        parser.next()
      }
    } finally {
      parser.close()
    }
    throw AssertionError("Web reading control preference is missing from navigation settings")
  }

  private class ControllerFixture(preserve: Boolean) {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    private var focusedNode: AccessibilityNodeInfoCompat? = null
    val controller: SelectorController

    init {
      FormFactorUtils.initialize(context)
      prefs.edit().clear()
          .putBoolean(context.getString(R.string.pref_preserve_web_reading_control_key), preserve)
          .putBoolean(context.getString(R.string.pref_selector_actions_key), true)
          .apply()
      val selectorPrefs = prefs.edit()
      Setting.values().forEach { selectorPrefs.putBoolean(context.getString(it.prefKeyResId), true) }
      selectorPrefs.apply()
      val focusMonitor = object : AccessibilityFocusMonitor(null, null, null) {
        override fun getAccessibilityFocus(useInputFocusIfEmpty: Boolean) = focusedNode

        override fun getEditingNodeFromFocusedKeyboard(node: AccessibilityNodeInfoCompat) = null
      }
      val menuCreator = object : NodeMenuRuleCreator(null, null, null, null) {
        override fun getNodeMenuByRule(
            rule: NodeMenuRuleCreator.MenuRules,
            context: Context,
            node: AccessibilityNodeInfoCompat,
            includeAncestors: Boolean,
        ): List<ContextMenuItem> =
            if (node.actionList.any { it.id == CUSTOM_ACTION_ID }) {
              listOf(ContextMenuItem(context, 0, CUSTOM_ACTION_ID, 0, "Custom action"))
            } else {
              emptyList()
            }
      }
      controller = SelectorController(
          context,
          Pipeline.FeedbackReturner { true },
          ActorState(null),
          focusMonitor,
          menuCreator,
          object : TalkBackAnalytics {},
          GestureShortcutMapping(context),
          Compositor.TextComposer { _, _, _ -> null },
          object : SelectorController.SelectorEventNotifier {
            override fun onSelectorSettingAnnounced(message: CharSequence) {}

            override fun onSelectorGranularityAdjusted() {}
          },
          null,
          null)
      // Supply table context directly without constructing the navigation actor and its service.
      val tableSetting = object : SelectorController.ContextualSetting {
        override fun getSetting() = Setting.GRANULARITY_ROW_COLUMN

        override fun isNodeSupportSetting(context: Context, node: AccessibilityNodeInfoCompat) =
            node.collectionInfo != null
      }
      ReflectionHelpers.setField(
          controller, "contextualSettings",
          ImmutableList.of(tableSetting, ActionsSetting(menuCreator, focusMonitor)))
    }

    fun seedSelection(setting: Setting) {
      prefs.edit().putString(
          context.getString(R.string.pref_current_selector_setting_key),
          context.getString(setting.prefValueResId)).apply()
    }

    fun focus(web: Boolean, actions: Boolean, table: Boolean = false) {
      val node = AccessibilityNodeInfo.obtain()
      if (web) node.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
      if (actions) {
        node.addAction(AccessibilityNodeInfo.AccessibilityAction(CUSTOM_ACTION_ID, "Custom action"))
      }
      if (table) node.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(2, 2, false)
      focusedNode = AccessibilityNodeInfoCompat.wrap(node)
      controller.newItemFocused(
          node,
          Interpretation.AccessibilityFocused.create(
              FocusActionInfo.builder().setSourceAction(FocusActionInfo.TOUCH_EXPLORATION).build(),
              false))
    }

    fun assertSelected(setting: Setting) {
      assertEquals(setting, SelectorController.getCurrentSetting(context))
    }
  }

  private companion object {
    const val CUSTOM_ACTION_ID = 0x01020001
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}
