/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Preserve master's container stops while optimizing only moves inside web content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebDocumentBoundaryTest {
  private lateinit var processor: FocusProcessorForLogicalNavigation
  private val attempts = ArrayList<AccessibilityNodeInfoCompat>()
  private val nativeFilter = Filter.node { it.className == "android.widget.Button" }
  private var order = listOf<Node>()
  private var htmlAccepted = true
  private var nextId = 0

  private inner class Node(web: Boolean = false, label: String = "") :
    AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    private val identity = ++nextId
    var parentNode: Node? = null
    init {
      className = if (web) "android.webkit.WebView" else "android.widget.Button"
      text = label
      packageName = "example.web"
      isVisibleToUser = true
      if (web) {
        addAction(ACTION_NEXT_HTML_ELEMENT)
        addAction(ACTION_PREVIOUS_HTML_ELEMENT)
      }
    }
    override fun getParent(): AccessibilityNodeInfoCompat? = parentNode
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = identity
  }

  private val traversal = object : TraversalStrategy {
    override fun findFocus(start: AccessibilityNodeInfoCompat, direction: Int): AccessibilityNodeInfoCompat? {
      val index = order.indexOf(start)
      return order.getOrNull(index + if (direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) -1 else 1)
    }
    override fun focusFirst(root: AccessibilityNodeInfoCompat, direction: Int): AccessibilityNodeInfoCompat? =
      if (direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) order.lastOrNull() else order.firstOrNull()
    override fun getSpeakingNodesCache(): MutableMap<AccessibilityNodeInfoCompat, Boolean> = HashMap()
  }

  @Before fun setup() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    processor = FocusProcessorForLogicalNavigation(service, null, null, null, null,
      Compositor.TextComposer { _, _, _ -> null }, null)
    processor.setPipeline(Pipeline.FeedbackReturner { feedback ->
      attempts.add(feedback.failovers()[0].webAction()!!.target())
      htmlAccepted
    })
  }

  private fun find(start: Node, backward: Boolean = false, target: Int = NavigationTarget.TARGET_DEFAULT,
    method: String = "findTargetFromMiddlePivot"): Any {
    val action = NavigationAction.Builder()
      .setAction(NavigationAction.DIRECTIONAL_NAVIGATION).setTarget(target)
      .setDirection(if (backward) TraversalStrategy.SEARCH_FOCUS_BACKWARD else TraversalStrategy.SEARCH_FOCUS_FORWARD)
      .build()
    return ReflectionHelpers.callInstanceMethod(processor, method,
      from(AccessibilityNodeInfoCompat::class.java, start), from(NavigationAction::class.java, action),
      from(Filter::class.java, nativeFilter), from(TraversalStrategy::class.java, traversal),
      from(Performance.EventId::class.java, Performance.EVENT_ID_UNTRACKED))
  }

  private fun isWeb(result: Any) = ReflectionHelpers.callInstanceMethod<Boolean>(result, "isWebElement")
  private fun node(result: Any) = ReflectionHelpers.callInstanceMethod<AccessibilityNodeInfoCompat?>(result, "getNode")

  @Test fun forwardEntryStillStopsOnAnUnnamedContainer() {
    val doc = Node(true)
    assertSame(doc, node(find(doc)))
    assertTrue(attempts.isEmpty())
  }

  @Test fun forwardEntryStillStopsOnANamedContainer() {
    val doc = Node(true, "Article title")
    assertSame(doc, node(find(doc)))
    assertTrue(attempts.isEmpty())
  }

  @Test fun backwardEntryUsesHtmlForBothNamedAndUnnamedDocuments() {
    for (label in listOf("", "Article title")) {
      val doc = Node(true, label)
      assertTrue(isWeb(find(doc, backward = true)))
      assertSame(doc, attempts.last())
    }
    assertEquals(2, attempts.size)
  }

  @Test fun backwardExitDoesNotIntroduceAnExtraTitleStop() {
    val head = Node()
    val doc = Node(true, "Article title")
    val leaf = Node().apply {
      parentNode = doc
      addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT)
    }
    order = listOf(head, doc)
    assertSame(head, node(find(leaf, backward = true, method = "findTargetOutsideWebElement")))
    assertTrue(attempts.isEmpty())
  }

  @Test fun backwardFromTheContainerKeepsNativeTraversal() {
    val head = Node()
    val doc = Node(true, "Article title")
    order = listOf(head, doc)
    assertSame(head, node(find(doc, backward = true, method = "findTargetFromWebElement")))
    assertTrue(attempts.isEmpty())
  }

  @Test fun rejectedBackwardEntryFallsBackAfterOneHtmlAttempt() {
    val head = Node()
    val doc = Node(true)
    order = listOf(head, doc)
    htmlAccepted = false
    assertSame(head, node(find(doc, backward = true)))
    assertEquals(listOf(doc), attempts)
  }

  @Test fun forwardExitKeepsTheExistingNativeFilter() {
    val doc = Node(true)
    val otherDoc = Node(true)
    val tail = Node()
    order = listOf(doc, otherDoc, tail)
    assertSame(tail, node(find(doc, method = "findTargetOutsideWebElement")))
    assertTrue(attempts.isEmpty())
  }
}
