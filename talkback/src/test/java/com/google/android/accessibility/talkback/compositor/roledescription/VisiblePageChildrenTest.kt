/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.compositor.roledescription

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisiblePageChildrenTest {
  private fun child(visible: Boolean) = AccessibilityNodeInfoCompat.wrap(
    AccessibilityNodeInfo(View(RuntimeEnvironment.getApplication())).apply { isVisibleToUser = visible }
  )

  private class Pages(val children: List<AccessibilityNodeInfoCompat?>) :
    AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    val queries = mutableListOf<Pair<Int, Int?>>()
    override fun getChildCount(): Int = children.size
    override fun getChild(index: Int): AccessibilityNodeInfoCompat? {
      queries += index to null
      return children[index]
    }
    override fun getChild(index: Int, strategy: Int): AccessibilityNodeInfoCompat? {
      queries += index to strategy
      return children[index]
    }
  }

  @Test fun aSingleVisiblePageKeepsItsPageDescription() {
    val pages = Pages(listOf(child(false), null, child(true), child(false)))
    assertTrue(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages))
    assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), pages.queries)
  }

  @Test fun twoVisiblePagesDoNotReadTheRemainingPages() {
    val pages = Pages(listOf(child(true), child(false), child(true), child(true), child(true)))
    assertFalse(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages))
    assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), pages.queries)
  }

  @Test fun noVisiblePageDoesNotUseTheSinglePageRole() {
    assertFalse(RoleDescriptionExtractor.hasExactlyOneVisibleChild(Pages(listOf(null, child(false)))))
    assertFalse(RoleDescriptionExtractor.hasExactlyOneVisibleChild(Pages(emptyList())))
  }

  @Test fun theKnownSoleVisibleChildDoesNotNeedToBeFetchedAgain() {
    val current=child(true); val pages=Pages(listOf(current))
    assertTrue(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages,current))
    assertTrue(pages.queries.isEmpty())
  }

  @Test fun theKnownSoleInvisibleChildDoesNotBecomeAPageAnnouncement() {
    val current=child(false); val pages=Pages(listOf(current))
    assertFalse(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages,current))
    assertTrue(pages.queries.isEmpty())
  }

  @Test fun knowingOneChildDoesNotSkipTheVisibilityOfOtherPages() {
    val current=child(true); val pages=Pages(listOf(current,child(false),child(true)))
    assertFalse(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages,current))
    assertEquals(listOf(0 to 3,1 to 3,2 to 3),pages.queries)
  }

  @Test @Config(sdk = [32]) fun olderAndroidUsesItsSupportedChildApi() {
    val pages = Pages(listOf(child(false), child(true)))
    assertTrue(RoleDescriptionExtractor.hasExactlyOneVisibleChild(pages))
    assertEquals(listOf(0 to null, 1 to null), pages.queries)
  }
}
