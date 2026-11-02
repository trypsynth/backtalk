/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FocusPathNodeQueriesTest {
  private class Node(label:String):AccessibilityNodeInfoCompat(
    AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication()))) {
    var owner:Node?=null
    val children=mutableListOf<Node>()
    val parentFlags=mutableListOf<Int>()
    val childFlags=mutableListOf<Int>()
    init {text=label;className="android.view.View"}
    fun add(n:Node){n.owner=this;children.add(n)}
    override fun getParent():AccessibilityNodeInfoCompat? {parentFlags.add(-1);return owner}
    override fun getParent(strategy:Int):AccessibilityNodeInfoCompat? {parentFlags.add(strategy);return owner}
    override fun getChildCount()=children.size
    override fun getChild(index:Int):AccessibilityNodeInfoCompat? {childFlags.add(-1);return children.getOrNull(index)}
    override fun getChild(index:Int,strategy:Int):AccessibilityNodeInfoCompat? {childFlags.add(strategy);return children.getOrNull(index)}
  }
  @Before fun clear(){NodeDescription.clearChildIndexHints()}
  private fun snapshots(path:NodePathDescription):List<NodeDescription> =
    ReflectionHelpers.getField(path,"nodeDescriptions")

  @Test fun aCompleteSnapshotKeepsAllLevelsIndicesAndSiblingTexts(){
    val root=Node("root");val group=Node("group");val first=Node("first");val leaf=Node("leaf");val last=Node("last")
    root.add(group);group.add(first);group.add(leaf);group.add(last)
    val records=snapshots(NodePathDescription.obtain(leaf))
    assertEquals(listOf("leaf","group","root"),records.map{it.text.toString()})
    assertEquals(listOf(1,0,-1),records.map{it.rawIndexInParent})
    assertEquals("first",records[0].previousSiblingText)
    assertEquals("last",records[0].nextSiblingText)
    assertEquals(1,leaf.parentFlags.size)
    assertEquals(1,group.parentFlags.size)
    assertEquals(1,root.parentFlags.size)
    assertTrue(listOf(leaf,group,root).flatMap{it.parentFlags}.all{it==AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS})
    assertTrue((group.childFlags+root.childFlags).all{it==0})
  }
  @Test fun aCyclicProviderCannotLoopOrRepeatAncestors(){
    val a=Node("a");val b=Node("b");a.add(b);b.add(a)
    val records=snapshots(NodePathDescription.obtain(a))
    assertEquals(2,records.size)
    assertEquals(listOf(a.hashCode(),b.hashCode()),records.map{it.nodeInfoHashCode})
  }
  @Test fun aPathLongerThanOnePrefetchBatchStillRetainsEveryAncestor(){
    val nodes=List(65){Node("level$it")}
    nodes.zipWithNext().forEach{(parent,child)->parent.add(child)}
    val records=snapshots(NodePathDescription.obtain(nodes.last()))
    assertEquals((64 downTo 0).map{"level$it"},records.map{it.text.toString()})
    assertTrue(nodes.all{it.parentFlags.size==1})
  }
  @Test @Config(sdk=[28]) fun olderAndroidKeepsTheLegacyQueriesAndCompleteSnapshot(){
    val root=Node("root");val leaf=Node("leaf");root.add(leaf)
    val records=snapshots(NodePathDescription.obtain(leaf))
    assertEquals(listOf("leaf","root"),records.map{it.text.toString()})
    assertEquals(listOf(-1),leaf.parentFlags)
    assertEquals(listOf(-1),root.parentFlags)
    assertTrue(root.childFlags.all{it== -1})
  }
}
