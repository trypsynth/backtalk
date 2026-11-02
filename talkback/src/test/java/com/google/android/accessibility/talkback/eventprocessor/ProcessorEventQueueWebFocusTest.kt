/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.eventprocessor

import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class ProcessorEventQueueWebFocusTest {
  private fun focus(label:String, web:Boolean):AccessibilityEvent {
    val node=AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication())).apply {
      className="android.widget.Button"
      text=label
      if(web) addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    }
    return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED).apply {
      contentDescription=label
      shadowOf(this).setSourceNode(node)
    }
  }

  @Test fun webFocusBurstIsDeliveredInOrderWhileContentQueueIsStillBlocked() {
    val delivered=mutableListOf<String>()
    val queue=ProcessorEventQueue { e, _ -> delivered.add(e.contentDescription.toString()) }
    // The real reader yields between batches of content-change events. Without immediate web
    // focus dispatch, a burst while that handler is waiting prunes every focus except the last two.
    repeat(120) { index ->
      queue.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        contentDescription="content-$index"
      },null)
    }
    repeat(80) { index ->
      val event=focus("item-$index",true)
      queue.onAccessibilityEvent(event,null)
      // A virtualized provider may discard or reuse this row as the next swipe scrolls it away.
      // It must already have been delivered, with its own source, before that happens.
      assertEquals("item-$index",delivered.last())
      shadowOf(event).setSourceNode(null)
    }
    assertEquals((0 until 80).map{"item-$it"},delivered)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(200,delivered.size)
    assertEquals((0 until 120).map{"content-$it"},delivered.drop(80))
  }

  @Test fun nativeFocusKeepsItsExistingQueuedBehavior() {
    val delivered=mutableListOf<String>()
    val queue=ProcessorEventQueue { e, _ -> delivered.add(e.contentDescription.toString()) }
    queue.onAccessibilityEvent(focus("native-first",false),null)
    queue.onAccessibilityEvent(focus("native-second",false),null)
    assertTrue(delivered.isEmpty())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf("native-first","native-second"),delivered)
  }

  @Test fun missingWebSourceStaysOnNormalPathWithoutDroppingEvent() {
    val delivered=mutableListOf<Int>()
    val queue=ProcessorEventQueue { e, _ -> delivered.add(e.eventType) }
    val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    queue.onAccessibilityEvent(event,null)
    assertTrue(delivered.isEmpty())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED),delivered)
  }

  @Test fun scrollAndWindowEventsDoNotEnterImmediateFocusPath() {
    val delivered=mutableListOf<Int>()
    val queue=ProcessorEventQueue { e, _ -> delivered.add(e.eventType) }
    val types=listOf(AccessibilityEvent.TYPE_VIEW_SCROLLED,AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    types.forEach { type -> queue.onAccessibilityEvent(AccessibilityEvent.obtain(type),null) }
    assertTrue(delivered.isEmpty())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(types,delivered)
  }
}
