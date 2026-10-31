/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** A successful HTML action must not ask the window for a native traversal tree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNavigationTraversalTest {
  private lateinit var processor: FocusProcessorForLogicalNavigation
  private val order = ArrayList<String>()
  private var accepted = true

  @Before fun setup() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    TraversalTreeCache.clear("test setup")
    processor = FocusProcessorForLogicalNavigation(service, null, null, null, null,
      Compositor.TextComposer { _, _, _ -> null }, null)
    processor.setPipeline(Pipeline.FeedbackReturner { order.add("html"); accepted })
  }

  @After fun cleanup() { TraversalTreeCache.clear("test cleanup") }

  private inner class Pivot(web: Boolean = true, container: Boolean = false) :
    AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    var canRefresh = true
    override fun refresh(): Boolean { order.add("refresh"); return canRefresh }
    init {
      className = if (container) "android.webkit.WebView" else "android.widget.Button"
      packageName = "example.web"
      if (web) {
        addAction(ACTION_NEXT_HTML_ELEMENT)
        addAction(ACTION_PREVIOUS_HTML_ELEMENT)
      }
    }
    override fun getWindow(): AccessibilityWindowInfoCompat? {
      order.add("native root")
      throw RootRequested()
    }
  }

  private class RootRequested : RuntimeException()

  private fun processorWithValidatedFocus(pivot: AccessibilityNodeInfoCompat): FocusProcessorForLogicalNavigation {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    val monitor = object : AccessibilityFocusMonitor(service, null, null) {
      override fun getAccessibilityFocus(useInputFocusIfEmpty: Boolean, requireEditable: Boolean,
        isCurrent: Filter<AccessibilityNodeInfoCompat>?): AccessibilityNodeInfoCompat = pivot
    }
    return FocusProcessorForLogicalNavigation(service, null, monitor, null, null,
      Compositor.TextComposer { _, _, _ -> null }, null).apply {
      setPipeline(Pipeline.FeedbackReturner { order.add("html"); true })
    }
  }

  private fun action(direction: Int, granularity: CursorGranularity? = null, target: Int = NavigationTarget.TARGET_DEFAULT) =
    NavigationAction.Builder().setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setDirection(direction).setTarget(target).setOriginalNavigationGranularity(granularity).build()

  private fun navigate(pivot: AccessibilityNodeInfoCompat, action: NavigationAction, ignore: Boolean = false) =
    processor.onDirectionalNavigationAction(pivot, ignore, action, EVENT_ID_UNTRACKED)

  private fun expectsNativeRoot(pivot: AccessibilityNodeInfoCompat, action: NavigationAction, ignore: Boolean = false) {
    assertThrows(RootRequested::class.java) { navigate(pivot, action, ignore) }
  }

  @Test fun forwardInsideWebDoesNotReadNativeRoot() {
    assertTrue(navigate(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_FORWARD)))
    assertEquals(listOf("html"), order)
  }

  @Test fun validatedAccessibilityFocusIsNotRefreshedTwice() {
    val pivot = Pivot().apply { isAccessibilityFocused = true }
    assertTrue(processorWithValidatedFocus(pivot).onNavigationAction(
      action(TraversalStrategy.SEARCH_FOCUS_FORWARD), EVENT_ID_UNTRACKED))
    assertEquals(listOf("html"), order)
  }

  @Test fun inputFocusFallbackStillValidatesThePivot() {
    val pivot = Pivot()
    assertTrue(processorWithValidatedFocus(pivot).onNavigationAction(
      action(TraversalStrategy.SEARCH_FOCUS_FORWARD), EVENT_ID_UNTRACKED))
    assertEquals(listOf("refresh", "html"), order)
  }

  @Test fun aRemovedInputFocusDoesNotBecomeTheNavigationPivot() {
    val pivot = Pivot().apply { canRefresh = false }
    assertFalse(processorWithValidatedFocus(pivot).onNavigationAction(
      action(TraversalStrategy.SEARCH_FOCUS_FORWARD), EVENT_ID_UNTRACKED))
    assertEquals(listOf("refresh"), order)
  }

  @Test fun backwardInsideWebDoesNotReadNativeRoot() {
    assertTrue(navigate(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_BACKWARD)))
    assertEquals(listOf("html"), order)
  }

  @Test fun successfulWebNavigationClearsAnEarlierWindowEdge() {
    ReflectionHelpers.setField(processor, "reachEdge", true)
    assertTrue(navigate(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_FORWARD)))
    assertFalse(ReflectionHelpers.getField<Boolean>(processor, "reachEdge"))
  }

  @Test fun everyCommandIsSentEvenInAnImmediateAlternatingBurst() {
    val pivot = Pivot()
    repeat(80) { i ->
      assertTrue(navigate(pivot, action(if (i % 2 == 0) TraversalStrategy.SEARCH_FOCUS_FORWARD else TraversalStrategy.SEARCH_FOCUS_BACKWARD)))
    }
    assertEquals(List(80) { "html" }, order)
  }

  @Test fun rejectedWebActionFallsBackAfterOneAttempt() {
    accepted = false
    expectsNativeRoot(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_FORWARD))
    assertEquals(listOf("html", "native root"), order)
  }

  @Test fun backwardFromTheWebContainerKeepsNativeTraversal() {
    expectsNativeRoot(Pivot(container = true), action(TraversalStrategy.SEARCH_FOCUS_BACKWARD))
    assertEquals(listOf("native root"), order)
  }

  @Test fun nativeNodesKeepNativeTraversal() {
    expectsNativeRoot(Pivot(web = false), action(TraversalStrategy.SEARCH_FOCUS_FORWARD))
    assertEquals(listOf("native root"), order)
  }

  @Test fun spatialNavigationKeepsItsTraversalStrategy() {
    expectsNativeRoot(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_LEFT))
    assertEquals(listOf("native root"), order)
  }

  @Test fun leavingMicroGranularityKeepsItsExistingTraversal() {
    expectsNativeRoot(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_FORWARD, CursorGranularity.CHARACTER))
    assertEquals(listOf("native root"), order)
  }

  @Test fun ignoringThePivotsDescendantsKeepsExistingTraversal() {
    expectsNativeRoot(Pivot(), action(TraversalStrategy.SEARCH_FOCUS_FORWARD), ignore = true)
    assertEquals(listOf("native root"), order)
  }

}
