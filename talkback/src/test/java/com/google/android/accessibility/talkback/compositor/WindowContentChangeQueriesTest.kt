/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.google.android.accessibility.talkback.compositor

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.compositor.rule.EventTypeWindowContentChangedFeedbackRule
import com.google.android.accessibility.talkback.compositor.roledescription.TreeNodesDescription
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.monitor.CollectionState
import com.google.android.accessibility.utils.monitor.InputModeTracker
import java.util.Locale
import java.util.function.Function
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Scrolling updates avoid unused IPC, while text, state and live announcements keep their rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowContentChangeQueriesTest {
  class Service : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
  }

  private class Node : AccessibilityNodeInfoCompat(
    AccessibilityNodeInfo(View(RuntimeEnvironment.getApplication()))
  ) {
    var parentQueries = 0
    var parentNode: AccessibilityNodeInfoCompat? = null
    override fun getParent(): AccessibilityNodeInfoCompat? {
      parentQueries++
      return parentNode
    }
  }

  private lateinit var service: Service
  private lateinit var globals: GlobalVariables

  @Before
  fun setUp() {
    service = Robolectric.setupService(Service::class.java)
    FormFactorUtils.initialize(service)
    globals = GlobalVariables(service, InputModeTracker(), CollectionState(), null)
    globals.setUserPreferredLocale(Locale.ENGLISH)
  }

  private fun feedback(node: Node, changes: Int, liveText: String = ""): EventFeedback {
    val rules = mutableMapOf<Int, Function<Compositor.HandleEventOptions, EventFeedback>>()
    val tree = object : TreeNodesDescription(service, null, globals, null) {
      override fun aggregateNodeTreeDescription(
        node: AccessibilityNodeInfoCompat?, event: AccessibilityEvent?
      ): CharSequence = liveText
    }
    EventTypeWindowContentChangedFeedbackRule.addFeedbackRule(rules, service, globals, null, tree)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      packageName = "example.embedded.web"
      contentChangeTypes = changes
    }
    return rules.getValue(Compositor.EVENT_TYPE_WINDOW_CONTENT_CHANGED)
      .apply(Compositor.HandleEventOptions().`object`(event).source(node))
  }

  @Test
  fun subtreeChangeDoesNotInspectParentFocusOrChromeAncestry() {
    val node = Node()
    assertEquals("", feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE).ttsOutput().get())
    assertEquals("A silent subtree change queried remote ancestors", 0, node.parentQueries)
  }

  @Test
  fun changedTextUnderTheFocusedNodeIsStillAnnounced() {
    val node = Node().apply {
      text = "Updated item"
      parentNode = Node().apply { isAccessibilityFocused = true }
    }
    val output = feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT).ttsOutput().get()
    assertEquals("Updated item", output.toString())
    assertEquals("Focus should be inspected once", 1, node.parentQueries)
  }

  @Test
  fun unfocusedChangedTextIsStillSilent() {
    val node = Node().apply { text = "Unfocused item" }
    assertEquals("", feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT).ttsOutput().get())
    assertEquals(1, node.parentQueries)
  }

  @Test
  fun liveRegionUpdatesRemainAnnouncedWithoutTheDisabledChromePolicy() {
    val node = Node().apply { liveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    assertEquals("Live update", feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
      "Live update").ttsOutput().get().toString())
    assertEquals(0, node.parentQueries)
  }

  @Test
  fun enabledStateStillUsesAncestorFocus() {
    val node = Node().apply {
      isEnabled = false
      parentNode = Node().apply { isAccessibilityFocused = true }
    }
    assertEquals(service.getString(R.string.value_disabled),
      feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_ENABLED).ttsOutput().get().toString())
    assertEquals(1, node.parentQueries)
  }

  @Test
  fun errorsRemainAnnouncedWithoutAnUnrelatedFocusLookup() {
    val node = Node().apply { isContentInvalid = true; error = "Invalid value" }
    assertTrue(feedback(node, AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR)
      .ttsOutput().get().toString().contains("Invalid value"))
    assertEquals(0, node.parentQueries)
  }
}
