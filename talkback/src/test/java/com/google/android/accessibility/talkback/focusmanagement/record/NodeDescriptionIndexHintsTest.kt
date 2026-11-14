/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NodeDescriptionIndexHintsTest {
  private fun node()=AccessibilityNodeInfoCompat.wrap(
    AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication())))

  private inner class Parent(size:Int): AccessibilityNodeInfoCompat(
    AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication()))) {
    val nodes=MutableList(size){node()}
    val reads=mutableListOf<Int>()
    var onRead:(()->Unit)?=null
    init {className="android.widget.LinearLayout"}
    override fun getChildCount()=nodes.size
    override fun getChild(index:Int):AccessibilityNodeInfoCompat? {
      reads.add(index)
      val result=nodes.getOrNull(index)
      onRead?.invoke()
      return result
    }
    override fun getChild(index:Int, prefetchingStrategy:Int):AccessibilityNodeInfoCompat? = getChild(index)
  }
  private fun index(target:AccessibilityNodeInfoCompat,parent:Parent)=
    NodeDescription.getRawIndexInParent(AccessibilityNode.takeOwnership(target)!!,
      AccessibilityNode.takeOwnership(parent))

  @Before fun clear(){NodeDescription.clearChildIndexHints()}

  @Test fun successiveSiblingsInALargeParentDoNotRepeatTheRemotePrefixScan(){
    val p=Parent(600)
    assertEquals(450,index(p.nodes[450],p))
    p.reads.clear()
    assertEquals(451,index(p.nodes[451],p))
    assertTrue("Read ${p.reads.size} children",p.reads.size<=3)
    p.reads.clear()
    assertEquals(450,index(p.nodes[450],p))
    assertTrue(p.reads.size<=3)
  }
  @Test fun anUnchangedAncestorNeedsOnlyOneChildRead(){
    val p=Parent(200);val target=p.nodes[170]
    assertEquals(170,index(target,p));p.reads.clear()
    assertEquals(170,index(target,p));assertEquals(listOf(170),p.reads)
  }
  @Test fun reorderingWithTheSameChildCountIsVerifiedAndFallsBack(){
    val p=Parent(100);val target=p.nodes[90]
    assertEquals(90,index(target,p))
    val other=p.nodes[5];p.nodes[5]=target;p.nodes[90]=other
    assertEquals(5,index(target,p))
  }
  @Test fun insertionAndRemovalCannotTurnAHintIntoTheWrongPosition(){
    val p=Parent(100);val target=p.nodes[80]
    assertEquals(80,index(target,p))
    repeat(12){p.nodes.add(0,node())}
    assertEquals(92,index(target,p))
    repeat(30){p.nodes.removeAt(0)}
    assertEquals(62,index(target,p))
    p.nodes.remove(target)
    assertEquals(NodeDescription.UNDEFINED_INDEX,index(target,p))
  }
  @Test fun aDifferentParentAndAMissingNodeStillUseTheirActualChildren(){
    val a=Parent(120);assertEquals(110,index(a.nodes[110],a))
    val b=Parent(4);assertEquals(2,index(b.nodes[2],b))
    assertEquals(NodeDescription.UNDEFINED_INDEX,index(a.nodes[110],b))
  }
  @Test fun aParentChangingDuringHintProbesStillGetsTheCompleteFallbackScan(){
    val p=Parent(100);assertEquals(90,index(p.nodes[90],p))
    val target=p.nodes[5];p.reads.clear()
    p.onRead={
      if(p.reads.size==3){
        val old=p.nodes[90];p.nodes[90]=target;p.nodes[5]=old
      }
    }
    assertEquals(90,index(target,p))
  }
}
