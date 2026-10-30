/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.interpreters

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.ActorState
import com.google.android.accessibility.talkback.ActorStateWritable
import com.google.android.accessibility.talkback.Interpretation
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenState
import com.google.android.accessibility.talkback.focusmanagement.interpreter.ScreenStateMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.monitor.InputMethodMonitor
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DelayedManualScrollFocusTest {
  private lateinit var service: TalkBackService
  private lateinit var history: AccessibilityFocusActionHistory
  private lateinit var interpreter: AccessibilityFocusInterpreter
  private var current: AccessibilityNodeInfoCompat? = null
  private var recoveries=0

  private inner class Node(label: String, document: Boolean = false) :
    AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain(View(service))) {
    var parentNode: Node? = null
    init {
      text=label; uniqueId=label; packageName="fixture.web"
      className=if(document) "android.webkit.WebView" else "android.widget.Button"
      isVisibleToUser=false; isEnabled=true; isFocusable=true; isClickable=!document
      addAction(ACTION_NEXT_HTML_ELEMENT)
    }
    override fun getParent(): AccessibilityNodeInfoCompat? = parentNode
  }

  @Before fun setup() {
    service=Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    history=AccessibilityFocusActionHistory(service)
    val monitor=object : AccessibilityFocusMonitor(service,null,history.reader) {
      override fun getAccessibilityFocus(useInputFocusIfEmpty: Boolean) = current
    }
    val screen=ScreenStateMonitor(service,InputMethodMonitor(service))
    ReflectionHelpers.setField(screen,"areMainWindowsStable",true)
    ReflectionHelpers.setField(screen,"stableScreenState",ScreenState(null,null,0,false))
    interpreter=AccessibilityFocusInterpreter(service,monitor,screen.state,null)
    interpreter.setActorState(ActorState(ActorStateWritable(null,null,null,null,history.reader,
      null,null,null,null,null,null,null,null,null)))
    interpreter.setPipeline(Pipeline.InterpretationReceiver {_,_,interpretation,_ ->
      if(interpretation is Interpretation.ManualScroll) recoveries++
      true
    })
  }

  private fun focus(node: Node?) {
    current=node
    if(node!=null)history.onAccessibilityFocusAction(node,FocusActionInfo.builder()
      .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),SystemClock.uptimeMillis(),null)
  }

  private fun queueScroll(document: Node?) {
    val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    if(document!=null)shadowOf(e).setSourceNode(document.unwrap())
    interpreter.onManualScroll(ManualScrollInterpreter.ManualScrollInterpretation.create(null,e,
      TraversalStrategy.SEARCH_FOCUS_FORWARD))
  }

  private fun settle() {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))}

  @Test fun oldDocumentScrollCannotStealFocusAfterACursorMoveToAnotherDocument() {
    val first=Node("doc-a",true); val second=Node("doc-b",true)
    focus(Node("item-a").apply {parentNode=first}); queueScroll(first)
    focus(Node("item-b").apply {parentNode=second}); settle()
    assertEquals(0,recoveries)
  }

  @Test fun scrollingTheCurrentDocumentStillRecoversAnInvisibleFocus() {
    val doc=Node("doc",true)
    focus(Node("item").apply {parentNode=doc}); queueScroll(doc); settle()
    assertEquals(1,recoveries)
  }

  @Test fun anotherItemWithinTheSameDocumentDoesNotDisableScrollRecovery() {
    val doc=Node("doc",true)
    focus(Node("old").apply {parentNode=doc}); queueScroll(doc)
    focus(Node("new").apply {parentNode=doc}); settle()
    assertEquals(1,recoveries)
  }

  @Test fun aLostFocusCanStillBeRecoveredInsideItsLastDocument() {
    val doc=Node("doc",true)
    focus(Node("item").apply {parentNode=doc}); queueScroll(doc)
    current=null; settle()
    assertEquals(1,recoveries)
  }

  @Test fun aRemovedScrollSourceDoesNotRequestRecovery() {
    focus(Node("item").apply {parentNode=Node("doc",true)}); queueScroll(null); settle()
    assertEquals(0,recoveries)
  }
}
