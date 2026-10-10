/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

/** Immutable values only: bookmarks must survive recycled views and never keep old node trees. */
internal data class RememberedScreen(
  val packageName: String,
  val title: String = "",
  val pane: String = "",
  val document: String = "",
  val structure: List<String> = emptyList(),
  val context: List<String> = emptyList(),
)

internal data class RememberedItem(
  val uniqueId: String = "",
  val viewId: String = "",
  val className: String = "",
  val role: Int = 0,
  val text: String = "",
  val description: String = "",
  val ancestors: List<String> = emptyList(),
  val previous: String = "",
  val next: String = "",
  val container: String = "",
  val row: Int = -1,
  val column: Int = -1,
) {
  val hasContent: Boolean get() = text.isNotEmpty() || description.isNotEmpty()

  /** Indices, bounds, and source node IDs are deliberately not evidence of item identity. */
  fun matchScore(other: RememberedItem): Int {
    if (className != other.className || role != other.role) return 0
    if (viewId.isNotEmpty() && other.viewId.isNotEmpty() && viewId != other.viewId) return 0
    if (hasContent && (text != other.text || description != other.description)) return 0
    if (uniqueId.isNotEmpty() && uniqueId == other.uniqueId) return 100
    if (!hasContent || !other.hasContent) return 0
    val context = ancestors.isNotEmpty() && ancestors == other.ancestors
    // An adjacent duplicate of the same title cannot distinguish which duplicate this is.
    val before = previous.isNotEmpty() && previous != text && previous == other.previous
    val after = next.isNotEmpty() && next != text && next == other.next
    if (!context && !before && !after) return 0
    return 1 + (if (context) 2 else 0) + (if (before) 2 else 0) + (if (after) 2 else 0)
  }
}

internal data class ScreenBookmark(
  val screen: RememberedScreen,
  val anchors: Set<String>,
  val item: RememberedItem,
)

/** Session-only history and cancellation/deadline policy, independent of Android node lifetimes. */
internal class ScreenFocusBookmarks {
  companion object {
    const val MAX_BOOKMARKS = 20
    const val RESTORE_TIMEOUT_MS = 3000L
    const val MAX_SCROLL_ACTIONS = 10
  }

  private val bookmarks = LinkedHashMap<RememberedScreen, ScreenBookmark>(16, 0.75f, true)
  var activeScreen: RememberedScreen? = null
    private set
  private var pendingSince: Long? = null
  private var scrollActions = 0
  private var lastViewport: List<RememberedItem>? = null
  val size: Int get() = bookmarks.size
  fun contains(screen: RememberedScreen): Boolean = bookmarks.containsKey(screen)

  fun remember(screen: RememberedScreen, anchors: Set<String>, item: RememberedItem) {
    if (!item.hasContent && item.uniqueId.isEmpty()) return
    bookmarks[screen] = ScreenBookmark(screen, anchors.toSet(), item)
    while (bookmarks.size > MAX_BOOKMARKS) bookmarks.remove(bookmarks.keys.first())
  }

  fun enter(screen: RememberedScreen?, now: Long, explicitTransition: Boolean = false,
    allowRestore: Boolean = true) {
    if (screen == activeScreen && !explicitTransition) return
    activeScreen = screen
    pendingSince = if (allowRestore && screen != null && bookmarks.containsKey(screen)) now else null
    scrollActions = 0
    lastViewport = null
  }

  fun pending(now: Long): ScreenBookmark? {
    val since = pendingSince ?: return null
    if (now < since || now - since >= RESTORE_TIMEOUT_MS) {
      cancel()
      return null
    }
    return bookmarks[activeScreen]
  }

  fun remaining(now: Long): Long =
    pendingSince?.let { (RESTORE_TIMEOUT_MS - (now - it)).coerceAtLeast(0) } ?: 0

  fun find(bookmark: ScreenBookmark, items: List<RememberedItem>): Int? {
    val scored = items.mapIndexed { index, item -> index to bookmark.item.matchScore(item) }
    val best = scored.maxOfOrNull { it.second } ?: return null
    if (best == 0) return null
    return scored.filter { it.second == best }.singleOrNull()?.first
  }

  /** Scrolling needs independent screen evidence because the target is not yet available. */
  fun mayScroll(bookmark: ScreenBookmark, anchors: Set<String>): Boolean =
    bookmark.anchors.intersect(anchors).isNotEmpty()

  fun beginScroll(now: Long, viewport: List<RememberedItem>): Boolean {
    if (pending(now) == null || scrollActions >= MAX_SCROLL_ACTIONS || lastViewport == viewport) {
      cancel()
      return false
    }
    scrollActions++
    lastViewport = viewport.toList()
    return true
  }

  fun cancel() {
    pendingSince = null
  }

  fun clear() {
    cancel()
    bookmarks.clear()
    activeScreen = null
    lastViewport = null
  }
}
