/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.input.ScrollEventInterpreter
import com.google.android.accessibility.utils.monitor.TouchMonitor
import com.google.android.accessibility.utils.output.ScrollActionRecord
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], shadows=[ScrollSourceRecordShadow::class])
class ScrollSourceSnapshotTest {
  private lateinit var interpreter: ScrollEventInterpreter
  private val results = mutableListOf<ScrollEventInterpreter.ScrollEventInterpretation>()
  private var active: ScrollActionRecord? = null
  private var finished: ScrollActionRecord? = null

  @Before fun setup() {
    interpreter=ScrollEventInterpreter(null,TouchMonitor(),false)
    interpreter.setScrollActorState {active}
    interpreter.setFinishedScrollActionRecord {finished}
    interpreter.addListener {_, result, _ -> results.add(result)}
  }

  private fun node(actions: Boolean=true) = AccessibilityNodeInfo.obtain(
    View(RuntimeEnvironment.getApplication())).apply {
    isScrollable=true
    if(actions)addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
  }

  private fun event(source: AccessibilityNodeInfo?, y: Int=100,
      type: Int=AccessibilityEvent.TYPE_VIEW_SCROLLED) = AccessibilityEvent.obtain(type).apply {
    scrollY=y; maxScrollY=2000; eventTime=1100
    contentChangeTypes=AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
    Shadow.extract<ScrollSourceRecordShadow>(this).setSourceNode(source)
  }

  private fun send(event: AccessibilityEvent) {
    interpreter.onAccessibilityEvent(event,null)
  }

  private fun sourceReads(event: AccessibilityEvent) =
    Shadow.extract<ScrollSourceRecordShadow>(event).sourceReads

  private fun record(source: AccessibilityNodeInfo, time: Long=1000) =
    ScrollActionRecord(7,null,AccessibilityNodeInfoCompat.wrap(source),
      ScrollActionRecord.ACTION_SCROLL_COMMAND,time,"fixture")

  @Test fun duplicatePositionsAndBothDirectionsStillUseTheSameSourceSnapshot() {
    val source=node()
    val events=listOf(event(source,100),event(source,200),event(source,200),event(source,100))
    events.forEach(::send)
    assertEquals(listOf(TraversalStrategy.SEARCH_FOCUS_UNKNOWN,
      TraversalStrategy.SEARCH_FOCUS_FORWARD,TraversalStrategy.SEARCH_FOCUS_UNKNOWN,
      TraversalStrategy.SEARCH_FOCUS_BACKWARD),results.map {it.scrollDirection})
    assertEquals(listOf(false,false,true,false),results.map {it.isDuplicateEvent})
    assertTrue(results.all {it.hasValidIndex && it.isFromScrollable})
    assertTrue(events.all {sourceReads(it)==1})
  }

  @Test fun aNewSourceOnTheNextEventDoesNotReuseThePreviousNodesPosition() {
    send(event(node(),100))
    val next=event(node(),900)
    send(next)
    assertEquals(TraversalStrategy.SEARCH_FOCUS_UNKNOWN,results.last().scrollDirection)
    assertFalse(results.last().isDuplicateEvent)
    assertEquals(1,sourceReads(next))
  }

  @Test fun activeAutoScrollMatchesItsNodeWithoutAnotherRemoteFetch() {
    val source=node(); active=record(source)
    val e=event(source); send(e)
    assertEquals(7,results.single().scrollInstanceId)
    assertEquals(ScrollActionRecord.ACTION_SCROLL_COMMAND,results.single().userAction)
    assertEquals(1,sourceReads(e))
    send(event(node(),200))
    assertEquals(-1,results.last().scrollInstanceId)
    assertEquals(ScrollActionRecord.ACTION_UNKNOWN,results.last().userAction)
  }

  @Test fun aFinishedScrollOnlySuppressesTheMatchingNodesResidualMovement() {
    val source=node()
    send(event(source,100))
    finished=record(source)
    val e=event(source,200); send(e)
    assertEquals(TraversalStrategy.SEARCH_FOCUS_FORWARD,results.last().scrollDirection)
    assertEquals(ScrollActionRecord.ACTION_UNKNOWN,results.last().userAction)
    assertEquals(1,sourceReads(e))
    finished=null
    send(event(source,300))
    assertEquals(ScrollActionRecord.ACTION_MANUAL_SCROLL,results.last().userAction)
  }

  @Test fun contentChangesStillRequireRealScrollActionsAndCacheTheirPosition() {
    val source=node()
    val e=event(source,100,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); send(e)
    assertTrue(results.last().hasValidIndex)
    assertFalse(results.last().isFromScrollable)
    send(event(source,200))
    assertEquals(TraversalStrategy.SEARCH_FOCUS_FORWARD,results.last().scrollDirection)
    val liar=event(node(false),300,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); send(liar)
    assertEquals(ScrollEventInterpreter.ScrollEventInterpretation.DEFAULT_INTERPRETATION,results.last())
    assertEquals(1,sourceReads(e))
    assertEquals(1,sourceReads(liar))
  }

  @Test fun windowChangesClearPositionsWithoutFetchingAnySource() {
    val source=node(); send(event(source,100))
    val window=event(source,type=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); send(window)
    send(event(source,200))
    assertEquals(TraversalStrategy.SEARCH_FOCUS_UNKNOWN,results.last().scrollDirection)
    assertTrue(sourceReads(window)==0)
    assertEquals(2,results.size)
  }

  @Test fun missingSourcesAndUnrelatedContentChangesKeepTheirExistingSemantics() {
    val missing=event(null); send(missing)
    assertEquals(ScrollEventInterpreter.ScrollEventInterpretation.DEFAULT_INTERPRETATION,results.single())
    assertEquals(1,sourceReads(missing))
    val unrelated=event(node(),type=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
      contentChangeTypes=AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
    }
    send(unrelated)
    assertEquals(1,results.size)
    assertTrue(sourceReads(unrelated)==0)
  }

  @Test @Config(sdk=[32]) fun olderAndroidFetchesOnceUsingItsSupportedApi() {
    val e=event(node()); send(e)
    assertTrue(results.single().hasValidIndex)
    assertEquals(1,sourceReads(e))
  }
}
