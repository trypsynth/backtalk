/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import org.junit.Assert.*
import org.junit.Test

class ScreenFocusBookmarksTest {
  private val history = ScreenFocusBookmarks()
  private val front = RememberedScreen("org.quantumbadger.redreader", "RedReader",
    structure = listOf("RecyclerView|posts"))
  private val detail = front.copy(structure = listOf("RecyclerView|comments"))
  private val post = RememberedItem(className = "TextView", viewId = "post_title", role = 1,
    text = "A post halfway down the list", ancestors = listOf("row", "RecyclerView|posts"),
    previous = "Earlier post", next = "Later post", container = "RecyclerView|posts", row = 30)
  private val anchors = setOf("toolbar:Front page")

  private fun returnToFront(): ScreenBookmark {
    history.remember(front, anchors, post)
    history.enter(detail, 1000)
    history.enter(front, 2000)
    return history.pending(2000)!!
  }

  @Test fun returnRestoresRecreatedItemInsteadOfToolbar() {
    val bookmark = returnToFront()
    val toolbar = post.copy(text = "Front page", viewId = "toolbar", row = -1)
    assertEquals(1, history.find(bookmark, listOf(toolbar, post.copy())))
  }

  @Test fun sameWindowCanHaveSeparateScreens() {
    history.remember(front, anchors, post)
    history.remember(detail, anchors, post.copy(text = "A comment"))
    history.enter(detail, 1000)
    assertEquals("A comment", history.pending(1000)!!.item.text)
    history.enter(front, 2000)
    assertEquals(post, history.pending(2000)!!.item)
  }

  @Test fun enteringWithAutomaticFocusDoesNotEraseBookmark() {
    val bookmark = returnToFront()
    history.enter(front, 2100)
    assertEquals(bookmark, history.pending(2100))
    assertEquals(0, history.find(bookmark, listOf(post)))
  }

  @Test fun recycledRowAndRowIndexDoNotIdentifyItem() {
    val bookmark = returnToFront()
    assertNull(history.find(bookmark, listOf(post.copy(text = "Another post"))))
  }

  @Test fun stableIdAlsoChecksRecycledContent() {
    val saved = post.copy(uniqueId = "stable")
    val bookmark = ScreenBookmark(front, anchors, saved)
    assertNull(history.find(bookmark, listOf(saved.copy(text = "Different post"))))
  }

  @Test fun stableIdCanRestoreUnlabelledItem() {
    val item = post.copy(uniqueId = "stable", text = "", description = "", ancestors = emptyList())
    val bookmark = ScreenBookmark(front, anchors, item)
    assertEquals(0, history.find(bookmark, listOf(item)))
  }

  @Test fun duplicateLabelsRequireUniqueContext() {
    val bookmark = returnToFront()
    assertNull(history.find(bookmark, listOf(post.copy(row = 2), post.copy(row = 30))))
    assertEquals(1, history.find(bookmark, listOf(
      post.copy(previous = "Different before", next = "Different after"), post.copy(row = 45))))
  }

  @Test fun reorderedItemsAreMatchedByContentRatherThanPosition() {
    val bookmark = returnToFront()
    assertEquals(0, history.find(bookmark,
      listOf(post.copy(row = 4, previous = "", next = ""))))
  }

  @Test fun removedItemDoesNotMatchAncestorOrNextRow() {
    val bookmark = returnToFront()
    assertNull(history.find(bookmark, listOf(post.copy(text = "Later post"),
      post.copy(className = "RecyclerView", text = post.text))))
  }

  @Test fun bareLabelWithoutContextIsInsufficient() {
    val item = post.copy(ancestors = emptyList(), previous = "", next = "")
    assertNull(history.find(ScreenBookmark(front, emptySet(), item), listOf(item)))
  }

  @Test fun browserDocumentsAndPackagesKeepSeparatePositions() {
    val page = front.copy(packageName = "com.android.chrome", document = "Page A|example.org/a")
    val other = page.copy(document = "Page B|example.org/b")
    history.remember(page, anchors, post)
    history.remember(other, anchors, post.copy(text = "Another heading"))
    history.enter(other, 1000)
    assertEquals("Another heading", history.pending(1000)!!.item.text)
    history.enter(page, 2000)
    assertEquals(post, history.pending(2000)!!.item)
    history.enter(page.copy(packageName = "org.mozilla.firefox"), 2100)
    assertNull(history.pending(2100))
  }

  @Test fun delayedContentCanMatchUntilThreeSecondDeadline() {
    val bookmark = returnToFront()
    assertNull(history.find(bookmark, emptyList()))
    history.enter(front, 4999)
    assertNotNull(history.pending(4999))
    assertEquals(1L, history.remaining(4999))
    assertNull(history.pending(5000))
  }

  @Test fun userInteractionCancelsRetriesUntilNextTransition() {
    returnToFront()
    history.cancel()
    history.enter(front, 2100)
    assertNull(history.pending(2100))
    history.enter(front, 2200, explicitTransition = true)
    assertNotNull(history.pending(2200))
  }

  @Test fun anotherScreenTransitionCancelsPreviousTarget() {
    returnToFront()
    history.enter(detail, 2100)
    assertNull(history.pending(2100))
  }

  @Test fun scrollRequiresCorroboratingScreenContent() {
    val bookmark = returnToFront()
    assertFalse(history.mayScroll(bookmark, setOf("toolbar:Other screen")))
    assertTrue(history.mayScroll(bookmark, anchors))
  }

  @Test fun scrollStopsWithoutProgress() {
    returnToFront()
    assertTrue(history.beginScroll(2100, listOf(post)))
    assertFalse(history.beginScroll(2200, listOf(post)))
    assertNull(history.pending(2200))
  }

  @Test fun scrollIsBoundedToTenActionsAndDeadline() {
    returnToFront()
    repeat(10) { assertTrue(history.beginScroll(2100 + it.toLong(), listOf(post.copy(row = it)))) }
    assertFalse(history.beginScroll(2200, listOf(post.copy(row = 11))))
    returnToFront()
    assertFalse(history.beginScroll(5000, listOf(post)))
  }

  @Test fun historyEvictsLeastRecentlyUsedScreenAtTwentyBookmarks() {
    history.remember(front, anchors, post)
    repeat(19) { history.remember(front.copy(title = "Screen $it"), anchors, post) }
    history.enter(front, 1000)
    history.pending(1000) // Access the oldest entry so another entry is evicted instead.
    history.remember(detail, anchors, post)
    assertEquals(20, history.size)
    history.enter(front.copy(title = "Screen 0"), 2000)
    assertNull(history.pending(2000))
    history.enter(front, 2100)
    assertNotNull(history.pending(2100))
  }

  @Test fun serviceStopClearsHistoryAndPendingWork() {
    returnToFront()
    history.clear()
    assertEquals(0, history.size)
    history.enter(front, 2100)
    assertNull(history.pending(2100))
  }
}
