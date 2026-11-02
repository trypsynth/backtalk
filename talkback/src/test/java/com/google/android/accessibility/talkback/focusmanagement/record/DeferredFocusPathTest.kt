/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DeferredFocusPathTest {
  private class Node(label:String):AccessibilityNodeInfoCompat(
    AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication()))) {
    var owner:Node?=null
    val children=mutableListOf<Node>()
    var childQuery:(()->Unit)?=null
    init {text=label;className="android.view.View";packageName="fixture.web"}
    fun add(n:Node){n.owner=this;children.add(n)}
    override fun getParent():AccessibilityNodeInfoCompat?=owner
    override fun getParent(strategy:Int):AccessibilityNodeInfoCompat?=owner
    override fun getChildCount()=children.size
    override fun getChild(index:Int):AccessibilityNodeInfoCompat? {
      childQuery?.invoke();return children.getOrNull(index)
    }
    override fun getChild(index:Int,strategy:Int):AccessibilityNodeInfoCompat?=getChild(index)
  }
  private fun snapshots(path:NodePathDescription):List<NodeDescription> =
    ReflectionHelpers.getField(path,"nodeDescriptions")

  @Test fun creatingCopyingAndAssociatingRecordsNeverQueriesSiblingContext(){
    val parent=Node("original parent");val leaf=Node("original leaf");parent.add(leaf)
    leaf.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    var childQueries=0;parent.childQuery={childQueries++}
    val record=FocusActionRecord(leaf,FocusActionInfo.builder().build(),1000)
    val copy=FocusActionRecord.copy(record)!!
    assertEquals(record,copy);assertEquals(record.hashCode(),copy.hashCode())
    assertTrue(record.ancestorIdentityPath.containsNodeByHashAndIdentity(parent))
    assertEquals(0,childQueries)
    // Recovery must still use the original ancestry/content when a provider reparents/reuses nodes.
    leaf.owner=null;leaf.text="changed leaf";parent.text="changed parent"
    val completed=snapshots(record.nodePathDescription)
    assertEquals(listOf("original leaf","original parent"),completed.map{it.text.toString()})
    assertTrue(childQueries>0)
    val beforeCopy=childQueries
    assertSame(record.nodePathDescription,copy.nodePathDescription)
    assertEquals(beforeCopy,childQueries)
  }

  @Test fun aThousandRecordsDoNoSpeculativeWorkAndOlderRecordsRemainRecoverable(){
    val path=NodePathDescription.captureIdentities(Node("root"))
    val runs=mutableListOf<Int>()
    val records=(0 until 1000).map{index->DeferredFocusPath(Callable{runs.add(index);path})}
    assertTrue(runs.isEmpty())
    assertSame(path,records[0].get());assertSame(path,records[500].get())
    assertSame(path,records[999].get())
    assertEquals(listOf(0,500,999),runs)
    assertSame(path,records[500].get());assertEquals(3,runs.size)
  }

  @Test fun recoveryPreservesAllSiblingLabelsAndPositions(){
    val parent=Node("parent");val first=Node("first");val leaf=Node("leaf");val last=Node("last")
    parent.add(first);parent.add(leaf);parent.add(last)
    val completed=DeferredFocusPath(NodePathDescription.captureIdentities(leaf)).get()
    val records=snapshots(completed)
    assertEquals(listOf("leaf","parent"),records.map{it.text.toString()})
    assertEquals(1,records[0].rawIndexInParent)
    assertEquals("first",records[0].previousSiblingText)
    assertEquals("last",records[0].nextSiblingText)
  }

  @Test fun ancestorAssociationNeedsNoSiblingQueries(){
    val root=Node("root");val document=Node("document");val leaf=Node("leaf")
    root.add(document);document.add(leaf)
    root.childQuery={fail("Identity capture read a child")}
    document.childQuery={fail("Identity capture read a child")}
    val path=NodePathDescription.captureIdentities(leaf)
    assertTrue(path.containsNodeByHashAndIdentity(document))
    assertTrue(path.containsNodeByHashAndIdentity(root))
    assertFalse(path.containsNodeByHashAndIdentity(Node("unrelated")))
  }

  @Test fun concurrentRecoveryComputesEachRecordExactlyOnce(){
    val path=NodePathDescription.captureIdentities(Node("root"))
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val runs=AtomicInteger()
    val failure=AtomicReference<Throwable?>()
    val record=DeferredFocusPath(Callable {
      runs.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));path
    })
    val first=Thread {try {assertSame(path,record.get())}catch(e:Throwable){failure.set(e)}}
    val second=Thread {try {assertSame(path,record.get())}catch(e:Throwable){failure.set(e)}}
    try {
      first.start();assertTrue(entered.await(5,TimeUnit.SECONDS));second.start()
    } finally {release.countDown()}
    first.join(5000);second.join(5000)
    assertFalse(first.isAlive);assertFalse(second.isAlive)
    failure.get()?.let{throw AssertionError(it)}
    assertEquals(1,runs.get())
  }
}
