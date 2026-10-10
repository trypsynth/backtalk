/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenFocusRestorerTest {
  private lateinit var restorer: ScreenFocusRestorer
  private lateinit var service: TalkBackService
  private lateinit var focusHistory: AccessibilityFocusActionHistory
  private val bookmarks = ScreenFocusBookmarks()
  private val feedback = ArrayList<Feedback>()
  private val createdNodes = ArrayList<AccessibilityNodeInfoCompat>()
  private var feedbackSucceeds = true
  private var currentContext: ScreenFocusRestorer.Context? = null

  @Before fun setUp() {
    service = Robolectric.buildService(TalkBackService::class.java).get()
    com.google.android.accessibility.utils.FormFactorUtils.initialize(service)
    val prefs = service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE)
    focusHistory = AccessibilityFocusActionHistory(service)
    restorer = ScreenFocusRestorer(service, prefs, Pipeline.FeedbackReturner {
      feedback.add(it)
      if (feedbackSucceeds) it.failovers().single().focus()?.target()?.let { target ->
        // Feedback targets are framework snapshots. Update the backing tree as the real focus
        // actor does, so repeated events can observe that restoration already succeeded.
        createdNodes.forEach { node -> node.isAccessibilityFocused = node == target }
      }
      feedbackSucceeds
    }, focusHistory.reader, bookmarks, { true }, { currentContext })
  }

  @After fun tearDown() { restorer.shutdown() }

  private fun node(text: String = "", className: String = "android.widget.TextView",
    viewId: String = "", parent: AccessibilityNodeInfoCompat? = null): AccessibilityNodeInfoCompat {
    val raw = AccessibilityNodeInfo.obtain()
    raw.packageName = "org.quantumbadger.redreader"
    raw.text = text
    raw.className = className
    raw.viewIdResourceName = viewId
    raw.isVisibleToUser = true
    raw.isImportantForAccessibility = true
    raw.setBoundsInScreen(Rect(0, 0, 100, 100))
    val result = AccessibilityNodeInfoCompat.wrap(raw)
    createdNodes.add(result)
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(raw)
    }
    return result
  }

  @Test fun recreatedWindowsProduceSameContentIdentity() {
    val first = node(className = "android.widget.FrameLayout")
    val second = node(className = "android.widget.FrameLayout")
    node(className = "androidx.recyclerview.widget.RecyclerView", viewId = "posts", parent = first)
      .isScrollable = true
    node(className = "androidx.recyclerview.widget.RecyclerView", viewId = "posts", parent = second)
      .isScrollable = true
    assertEquals(restorer.captureContext(first, "RedReader").screen,
      restorer.captureContext(second, "RedReader").screen)
  }

  @Test fun screenIdentityDoesNotDependOnVisibleRecycledRows() {
    val root = node(className = "android.widget.FrameLayout")
    val list = node(className = "androidx.recyclerview.widget.RecyclerView", viewId = "posts",
      parent = root)
    list.isScrollable = true
    val row = node("First visible post", parent = list)
    val initial = restorer.captureContext(root, "RedReader")
    row.text = "Different visible post"
    val changed = restorer.captureContext(root, "RedReader")
    assertEquals(initial.screen, changed.screen)
    assertEquals(initial.anchors, changed.anchors)
  }

  @Test fun changedListContainerDistinguishesDetailFromFrontPage() {
    val root = node(className = "android.widget.FrameLayout")
    val list = node(className = "androidx.recyclerview.widget.RecyclerView", viewId = "posts",
      parent = root)
    list.isScrollable = true
    val initial = restorer.captureContext(root, "RedReader")
    list.viewIdResourceName = "comments"
    assertNotEquals(initial.screen, restorer.captureContext(root, "RedReader").screen)
  }

  @Test fun rootListIdentityDoesNotIncludeVisibleHeadings() {
    val root = node(className = "android.widget.ListView", viewId = "posts")
    root.isScrollable = true
    val row = node("First post", parent = root)
    row.isHeading = true
    val initial = restorer.captureContext(root, "RedReader")
    row.text = "Different post"
    val changed = restorer.captureContext(root, "RedReader")
    assertEquals(initial.screen, changed.screen)
    assertEquals(initial.anchors, changed.anchors)
  }

  @Test fun labelsThatDifferAfterMemoryBoundDoNotMatch() {
    val root = node(className = "android.widget.FrameLayout")
    val row = node("a".repeat(600) + "Original", parent = root)
    val saved = restorer.describe(row)
    row.text = "a".repeat(600) + "Replacement"
    assertEquals(0, saved.matchScore(restorer.describe(row)))
    assertTrue(saved.text.length <= 512)
  }

  @Test fun detachedNodeCannotBecomeDestinationBookmark() {
    returningList()
    restorer.rememberBeforeActivation(node("Stale departing item"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    // Recording a detached focus event must not overwrite the valid bookmark.
    val saved = bookmarks.pending(android.os.SystemClock.uptimeMillis())
    assertTrue(saved == null || saved.item.text == "Remember this post")
    currentContext = currentContext!!.copy(screen = currentContext!!.screen.copy(pane = "Details"))
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    currentContext = currentContext!!.copy(screen = currentContext!!.screen.copy(pane = ""))
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals("Remember this post", feedback.last().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun detachedFocusDoesNotCancelPendingReturn() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.onFocusSet(node("Detached item"),
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build())
    settle()
    assertEquals("Remember this post", feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun itemSnapshotDoesNotChangeWhenViewIsRecycled() {
    val root = node(className = "android.widget.FrameLayout")
    val row = node("Original post", parent = root)
    val saved = restorer.describe(row)
    row.text = "Replacement post"
    assertEquals("Original post", saved.text)
    assertEquals(0, saved.matchScore(restorer.describe(row)))
  }

  @Test fun itemIncludesWrapperCollectionPositionAndNeighborText() {
    val root = node(className = "android.widget.ListView", viewId = "posts")
    root.isScrollable = true
    node("Previous post", parent = root)
    val wrapper = node(className = "android.widget.LinearLayout", parent = root)
    wrapper.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
      30, 1, 0, 1, false))
    val title = node("Post title", parent = wrapper)
    node("Next post", parent = root)
    val saved = restorer.describe(title)
    assertEquals(30, saved.row)
    assertEquals(0, saved.column)
    assertEquals("Previous post", saved.previous)
    assertEquals("Next post", saved.next)
    assertEquals("android.widget.ListView|posts", saved.container)
  }

  @Test fun nestedListsKeepNearestContainerNeighborsAndPosition() {
    val outer = node(className = "android.widget.ListView", viewId = "outer")
    outer.isScrollable = true
    node("Outer previous", parent = outer)
    val wrapper = node(className = "android.widget.LinearLayout", parent = outer)
    wrapper.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
      50, 1, 0, 1, false))
    val inner = node(className = "android.widget.ListView", viewId = "inner", parent = wrapper)
    inner.isScrollable = true
    node("Inner previous", parent = inner)
    val row = node("Target", parent = inner)
    node("Inner next", parent = inner)
    node("Outer next", parent = outer)
    val saved = restorer.describe(row)
    assertEquals("android.widget.ListView|inner", saved.container)
    assertEquals("Inner previous", saved.previous)
    assertEquals("Inner next", saved.next)
    assertEquals(-1, saved.row)
  }

  @Test fun wrapperTextDoesNotIncludeDescendantsOfEditableControls() {
    val wrapper = node(className = "android.widget.LinearLayout")
    val editor = node(className = "android.widget.EditText", parent = wrapper)
    editor.isEditable = true
    node("Private editing content", parent = editor)
    node("Public label", parent = wrapper)
    assertEquals("Public label", restorer.describe(wrapper).text)
  }

  @Test fun webDocumentTitlesKeepSeparatePositionsInSameWindow() {
    val root = node(className = "android.widget.FrameLayout")
    val web = node("Page A", "android.webkit.WebView", parent = root)
    val initial = restorer.captureContext(root, "Browser").screen
    web.text = "Page B"
    assertNotEquals(initial, restorer.captureContext(root, "Browser").screen)
  }

  @Test fun nativeHtmlDocumentDoesNotNeedWebViewClassToKeepPagesSeparate() {
    val root = node(className = "android.widget.FrameLayout")
    val html = node("Page A", "android.view.View", parent = root)
    html.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    node("A heading within the page", parent = html)
    val initial = restorer.captureContext(root, "Browser").screen
    html.text = "Page B"
    assertNotEquals(initial, restorer.captureContext(root, "Browser").screen)
  }

  @Test fun nativeBrowserAddressAlsoDistinguishesDocumentsWithNoTitle() {
    val root = node(className = "android.widget.FrameLayout")
    val address = node("example.org/a", viewId = "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
      parent = root)
    val initial = restorer.captureContext(root, "Browser").screen
    address.text = "example.org/b"
    assertNotEquals(initial, restorer.captureContext(root, "Browser").screen)
  }

  @Test fun scrollSettingIsOffAndKeyMatchesResource() {
    val prefs = service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE)
    assertFalse(prefs.getBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, false))
    assertEquals(ScreenFocusRestorer.SCROLL_PREFERENCE,
      service.getString(R.string.pref_scroll_to_restore_previous_focus_key))
  }

  private fun returningList(scrollAction: AccessibilityNodeInfoCompat.AccessibilityActionCompat? = null,
    position: Int = -1, prepareBack: Boolean = true):
    AccessibilityNodeInfoCompat {
    val root = node(className = "android.widget.FrameLayout")
    node("Front page", viewId = "toolbar_title", parent = root)
    val list = node(className = "android.widget.ListView", viewId = "posts", parent = root)
    list.isScrollable = true
    scrollAction?.let(list::addAction)
    val row = node("Remember this post", parent = list)
    if (position >= 0) row.setCollectionItemInfo(
      AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(position, 1, 0, 1, false))
    currentContext = restorer.captureContext(root, "RedReader")
    bookmarks.remember(currentContext!!.screen, currentContext!!.anchors, restorer.describe(row))
    if (prepareBack) {
      val destination = currentContext!!
      currentContext = destination.copy(screen = destination.screen.copy(pane = "Departing details"))
      restorer.requestBack()
      currentContext = destination
    }
    return row
  }

  private fun event(type: Int) {
    restorer.onAccessibilityEvent(AccessibilityEvent.obtain(type))
  }

  private fun browserContext(): ScreenFocusRestorer.Context {
    val root = node(className = "android.widget.FrameLayout")
    root.packageName = "com.android.chrome"
    val document = node("External post", "android.webkit.WebView", parent = root)
    document.packageName = "com.android.chrome"
    return restorer.captureContext(root, "Chrome")
  }

  @Test fun firstFocusEventCanFinishSaveAfterNodeAttaches() {
    val root = node(className = "android.widget.FrameLayout")
    node("Front page", viewId = "toolbar_title", parent = root)
    val list = node(className = "android.widget.ListView", viewId = "posts", parent = root)
    list.isScrollable = true
    val post = node("First opened post")
    currentContext = restorer.captureContext(root, "RedReader")
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    val time = android.os.SystemClock.uptimeMillis()
    focusHistory.onAccessibilityFocusAction(post, info, time, null)
    restorer.onFocusSet(post, info, time)
    assertEquals(0, bookmarks.size)
    shadowOf(list.unwrap()).addChild(post.unwrap())
    val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(focused).setSourceNode(post.unwrap())
    restorer.onAccessibilityEvent(focused)
    assertEquals(1, bookmarks.size)
    val front = currentContext!!
    currentContext = front.copy(screen = front.screen.copy(pane = "Comments"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    currentContext = front
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(post.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun quickFirstPostRoundTripDoesNotCarryDepartureInputVeto() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.rememberBeforeActivation(row)
    val front = currentContext!!
    currentContext = front.copy(screen = front.screen.copy(pane = "Comments"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    currentContext = front
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun externalBrowserRoundTripRestoresOriginatingPostWithoutSettlingInBrowser() {
    val row = returningList()
    restorer.rememberBeforeActivation(row)
    val origin = currentContext!!
    currentContext = browserContext()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    // The browser can close before the 120 ms restoration callback runs.
    restorer.requestBack()
    currentContext = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun browserInputDoesNotCancelRestorationAfterReturningToOrigin() {
    val row = returningList()
    restorer.rememberBeforeActivation(row)
    val origin = currentContext!!
    currentContext = browserContext()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.onUserInteraction()
    restorer.requestBack()
    currentContext = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun activationPreservesReturnEvenWhenDestinationRootWasNeverAvailable() {
    val row = returningList()
    restorer.rememberBeforeActivation(row)
    // The app redirected and returned while rootInActiveWindow was stale. Only the returning
    // screen's event is observable; the saved activation must still allow a return attempt.
    restorer.requestBack()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun inputOnOriginAfterBrowserReturnStillPreventsOverride() {
    val row = returningList()
    restorer.rememberBeforeActivation(row)
    val origin = currentContext!!
    currentContext = browserContext()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    currentContext = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.onUserInteraction()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun lateAutomaticBackButtonFocusCannotDisplaceRestoredFirstPost() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    val back = node("Back", "android.widget.Button", parent = currentContext!!.root)
    row.isAccessibilityFocused = false
    back.isAccessibilityFocused = true
    restorer.onFocusSet(back,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build())
    settle()
    assertEquals(2, feedback.size)
    assertEquals(row.text, feedback.last().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun userInputAfterRestorePreventsCorrectionOfLaterAutomaticFocus() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    restorer.onUserInteraction()
    row.isAccessibilityFocused = false
    restorer.onFocusSet(node("Back", "android.widget.Button", parent = currentContext!!.root),
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build())
    settle()
    assertEquals(1, feedback.size)
  }

  @Test fun appAssignedBackFocusWithoutFocusHistoryIsAlsoCorrected() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    row.isAccessibilityFocused = false
    val back = node("Back", "android.widget.Button", parent = currentContext!!.root)
    back.isAccessibilityFocused = true
    val assigned = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(assigned).setSourceNode(back.unwrap())
    restorer.onAccessibilityEvent(assigned)
    settle()
    assertEquals(2, feedback.size)
    assertEquals(row.text, feedback.last().failovers().single().focus()!!.target()!!.text)
  }

  private fun settle(ms: Long = 121L) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
  }

  @Test fun returningListReplacesAutomaticTopFocusWithRememberedPost() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    assertEquals("Remember this post", feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun delayedContentRetriesWithoutWaitingForDeadline() {
    val row = returningList()
    row.text = "Loading"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    row.text = "Remember this post"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(1, feedback.size)
  }

  @Test fun touchingDuringTransitionPreventsFocusOverride() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    settle()
    // A later content event cannot restart the cancelled transition.
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun touchingDuringContentOnlyTransitionPreventsFocusOverride() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    settle()
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun repeatedWindowEventCannotUndoTouchCancellation() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun repeatedWindowEventsDoNotExtendReconstructionDeadline() {
    val row = returningList()
    row.text = "Loading"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    repeat(4) {
      event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
      settle(800)
    }
    row.text = "Remember this post"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun repeatedWindowEventsDoNotRepeatSuccessfulRestoration() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
  }

  @Test fun keyInputCancelsPendingRestoration() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun editableInputFocusKeepsNormalInputFocusPolicy() {
    returningList()
    val editor = node("Editing", "android.widget.EditText", parent = currentContext!!.root)
    editor.isEditable = true
    editor.isFocused = true
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun automaticInitialFocusDoesNotReplaceSavedPost() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build()
    restorer.onFocusSet(node("Toolbar"), info)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun delayedNativeFocusEventDoesNotOverwriteDestinationBookmark() {
    val row = returningList()
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    val time = android.os.SystemClock.uptimeMillis()
    focusHistory.onAccessibilityFocusAction(row, info, time, null)
    restorer.onFocusSet(row, info, time)
    val destination = node("Destination item", parent = currentContext!!.root)
    restorer.requestBack()
    currentContext = currentContext!!.copy(screen = currentContext!!.screen.copy(pane = "Details"))
    bookmarks.remember(currentContext!!.screen, currentContext!!.anchors,
      restorer.describe(destination))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val delayed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(delayed).setSourceNode(row.unwrap())
    assertNotNull(focusHistory.reader.matchFocusActionRecordFromEvent(delayed))
    restorer.onAccessibilityEvent(delayed)
    settle()
    assertEquals(destination.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun defaultDoesNotScrollForOffscreenItem() {
    val row = returningList()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun visibleFlagOutsideWindowDoesNotCauseImplicitScrollingByDefault() {
    val row = returningList()
    row.setBoundsInScreen(Rect(0, 200, 100, 300))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun activationSavesInitialFocusWithoutAnEarlierSwipe() {
    val root = node(className = "android.widget.FrameLayout")
    val link = node("Open a page", parent = root)
    currentContext = restorer.captureContext(root, "Browser")
    restorer.rememberBeforeActivation(link)
    val front = currentContext!!
    currentContext = front.copy(screen = front.screen.copy(document = "Another page"))
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    restorer.requestBack()
    currentContext = front
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(link.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun enabledSettingShowsOffscreenItemBeforeFocusing() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id,
      feedback.single().failovers().single().nodeAction()!!.actionId())
    row.isVisibleToUser = true
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(300)
    assertEquals(2, feedback.size)
    assertNotNull(feedback.last().failovers().single().focus())
  }

  @Test fun showOnScreenAcceptsScrollEventFromContainingList() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    val scrolled = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scrolled).setSourceNode(row.parent.unwrap())
    row.isVisibleToUser = true
    restorer.onAccessibilityEvent(scrolled)
    settle()
    assertEquals(2, feedback.size)
    assertNotNull(feedback.last().failovers().single().focus())
  }

  @Test fun unsupportedScrollActionLeavesExistingFocus() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun unsupportedShowOnScreenFallsBackToSupportedContainerScroll() {
    val row = returningList(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
      feedback.single().failovers().single().nodeAction()!!.actionId())
    row.isVisibleToUser = true
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(300)
    assertEquals(2, feedback.size)
    assertNotNull(feedback.last().failovers().single().focus())
  }

  @Test fun failedPositionScrollStopsWithoutIssuingAnotherAction() {
    val row = returningList(
      AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION, 30)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.text = "Different visible post"
    row.setCollectionItemInfo(
      AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false))
    feedbackSucceeds = false
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id,
      feedback.single().failovers().single().nodeAction()!!.actionId())
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(400)
    assertEquals(1, feedback.size)
  }

  @Test fun containerSearchIssuesAtMostTenActionsEvenWhenContentKeepsChanging() {
    val row = returningList(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD, 100)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.text = "Loading first row"
    row.setCollectionItemInfo(
      AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    repeat(12) {
      row.text = "Loading row $it"
      event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
      settle(241)
    }
    assertEquals(10, feedback.size)
    assertTrue(feedback.all { it.failovers().single().nodeAction() != null })
  }

  @Test fun changedLabelAtVisibleSavedPositionDoesNotScroll() {
    val row = returningList(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION, 30)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.text = "Read state changed while returning"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    // Content reconstruction can finish without changing the viewport.
    row.text = "Remember this post"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertNotNull(feedback.single().failovers().single().focus())
  }

  @Test fun missingUnindexedItemDoesNotStartBlindContainerScrolling() {
    val row = returningList(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.text = "List still rebuilding"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    row.text = "Remember this post"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertNotNull(feedback.single().failovers().single().focus())
  }

  @Test fun visibleFocusFailureDoesNotFallThroughToScrolling() {
    val row = returningList(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    feedbackSucceeds = false
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertNotNull(feedback.single().failovers().single().focus())
    assertNull(feedback.single().failovers().single().nodeAction())
  }

  @Test fun visibleNonFocusableMatchDoesNotRequestShowOnScreen() {
    val root = node(className = "android.widget.FrameLayout")
    val list = node(className = "android.widget.ListView", viewId = "posts", parent = root)
    list.isScrollable = true
    list.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    val wrapper = node(className = "android.widget.LinearLayout", parent = list)
    val row = node("Remember this post", parent = wrapper)
    currentContext = restorer.captureContext(root, "App")
    bookmarks.remember(currentContext!!.screen, currentContext!!.anchors, restorer.describe(row))
    val destination = currentContext!!
    currentContext = destination.copy(screen = destination.screen.copy(pane = "Details"))
    restorer.requestBack()
    currentContext = destination
    // Reconstruction groups the visible label into its clickable row wrapper.
    wrapper.isClickable = true
    assertFalse(com.google.android.accessibility.utils.AccessibilityNodeInfoUtils.shouldFocusNode(row))
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.none { it.failovers().single().nodeAction() != null })
  }

  @Test fun redReaderReadStateAndChangingSubtitlePreservePostIdentity() {
    val root = node(className = "androidx.recyclerview.widget.RecyclerView")
    root.isScrollable = true
    val row = node(className = "android.widget.LinearLayout",
      viewId = "org.quantumbadger.redreader:id/reddit_post_layout_outer", parent = root)
    row.setCollectionItemInfo(
      AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false))
    val title = node("Read this post", viewId = "org.quantumbadger.redreader:id/reddit_post_title", parent = row)
    val subtitle = node("1 minute, 3 comments", viewId = "org.quantumbadger.redreader:id/reddit_post_subtitle", parent = row)
    currentContext = restorer.captureContext(root, "RedReader")
    restorer.rememberBeforeActivation(row)
    val saved = restorer.describe(row)
    currentContext = currentContext!!.copy(screen = currentContext!!.screen.copy(pane = "Post"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    currentContext = restorer.captureContext(root, "RedReader")
    title.contentDescription = "Read, Read this post"
    subtitle.text = "2 minutes, 4 comments"
    assertEquals("Read this post", restorer.describe(row).text)
    assertTrue(saved.matchScore(restorer.describe(row)) > 0)
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    root.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row, feedback.single().failovers().single().focus()!!.target())
  }

  @Test fun redReaderRecycledPostWithDifferentTitleCannotMatchSamePosition() {
    val row = node(className = "android.widget.LinearLayout",
      viewId = "org.quantumbadger.redreader:id/reddit_post_layout_outer")
    row.setCollectionItemInfo(
      AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false))
    val title = node("Original post", viewId = "org.quantumbadger.redreader:id/reddit_post_title", parent = row)
    val original = restorer.describe(row)
    title.text = "Different post"
    assertEquals(0, original.matchScore(restorer.describe(row)))
  }

  @Test fun primaryTitleAndChangingMetadataWorkInAnyApp() {
    val root = node(className = "android.widget.FrameLayout")
    root.packageName = "other.app"
    val row = node(className = "android.widget.LinearLayout",
      viewId = "other.app:id/article_card", parent = root)
    row.packageName = "other.app"
    row.isClickable = true
    val title = node("Post name", viewId = "other.app:id/article_title", parent = row)
    title.packageName = "other.app"
    val subtitle = node("Unread", parent = row)
    subtitle.packageName = "other.app"
    val original = restorer.describe(row)
    subtitle.text = "Read"
    title.contentDescription = "Read, Post name"
    assertEquals("Post name", restorer.describe(row).text)
    assertEquals(original.text, restorer.describe(row).text)
    assertEquals(original.description, restorer.describe(row).description)
    assertTrue(original.matchScore(restorer.describe(row)) > 0)
    // A different main title is still a different item even on the same recycled card.
    title.text = "Another post"
    assertEquals(0, original.matchScore(restorer.describe(row)))
  }

  @Test fun nativeRowHeadingWithoutResourceIdPreservesIdentity() {
    val root = node(className = "android.widget.ListView")
    root.isScrollable = true
    val row = node(className = "android.widget.LinearLayout", parent = root)
    val heading = node("Item heading", parent = row)
    heading.isHeading = true
    val status = node("Unread", parent = row)
    val original = restorer.describe(row)
    heading.contentDescription = "Read, Item heading"
    status.text = "Read"
    assertEquals("Item heading", original.text)
    assertTrue(original.matchScore(restorer.describe(row)) > 0)
  }

  @Test fun multipleTitleFieldsCannotHideChangedItemContent() {
    val root = node(className = "android.widget.ListView")
    root.isScrollable = true
    val row = node(className = "android.widget.LinearLayout", parent = root)
    node("Shared title", viewId = "other.app:id/title", parent = row)
    val other = node("Original title", viewId = "other.app:id/secondary_title", parent = row)
    val original = restorer.describe(row)
    other.text = "Replacement title"
    assertEquals(0, original.matchScore(restorer.describe(row)))
  }

  @Test fun screenToolbarTitleCannotHideChangedContent() {
    val root = node(className = "android.widget.FrameLayout")
    node("Shared title", viewId = "other.app:id/toolbar_title", parent = root)
    val content = node("Original item", parent = root)
    val original = restorer.describe(root)
    content.text = "Replacement item"
    assertEquals(0, original.matchScore(restorer.describe(root)))
  }

  @Test fun nestedTitleFocusUsesPrimaryTextInsteadOfChangingDescription() {
    val root = node(className = "android.widget.ListView")
    root.isScrollable = true
    val row = node(className = "android.widget.LinearLayout", parent = root)
    val wrapper = node(className = "android.widget.LinearLayout", parent = row)
    val title = node("Actual title", viewId = "other.app:id/title", parent = wrapper)
    val original = restorer.describe(title)
    title.contentDescription = "Read, Actual title"
    assertTrue(original.matchScore(restorer.describe(title)) > 0)
  }

  @Test fun duplicatePrimaryTitlesWithDifferentMetadataRemainAmbiguous() {
    val root = node(className = "android.widget.ListView")
    root.isScrollable = true
    val first = node(className = "android.widget.LinearLayout", parent = root)
    node("Same title", viewId = "other.app:id/title", parent = first)
    node("Unread, 1 comment", parent = first)
    val second = node(className = "android.widget.LinearLayout", parent = root)
    node("Same title", viewId = "other.app:id/title", parent = second)
    node("Read, 2 comments", parent = second)
    val screen = restorer.captureContext(root, "Other app").screen
    val saved = ScreenBookmark(screen, emptySet(), restorer.describe(first))
    assertNull(bookmarks.find(saved, listOf(restorer.describe(first), restorer.describe(second))))
  }

  @Test fun browserTitleFieldRetainsExistingFullDescriptionMatching() {
    val root = node(className = "android.webkit.WebView")
    val title = node("Web heading", viewId = "page_title", parent = root)
    title.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    val original = restorer.describe(title)
    title.contentDescription = "Different web content"
    assertEquals(0, original.matchScore(restorer.describe(title)))
  }

  @Test fun shutdownClearsBookmarksAndQueuedCallbacks() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.shutdown()
    settle()
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(0, bookmarks.size)
    assertTrue(feedback.isEmpty())
  }

  @Test fun sameWindowContentChangeCanRecognizeReturningScreen() {
    val row = returningList()
    val front = currentContext!!
    currentContext = front.copy(screen = front.screen.copy(pane = "Comments"))
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    restorer.requestBack()
    currentContext = front
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun contentAfterDeadlineDoesNotReclaimFocus() {
    val row = returningList()
    row.text = "Loading"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle(3200)
    row.text = "Remember this post"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun inputDuringScrollCannotReviveDelayedCallback() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    restorer.onUserInteraction()
    row.isVisibleToUser = true
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(400)
    assertEquals(1, feedback.size)
  }

  @Test fun lateAutomaticScrollFocusDoesNotOverwriteBookmark() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    settle(1100)
    val automaticTop = node("Automatic top", parent = currentContext!!.root)
    restorer.onFocusSet(automaticTop,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.MANUAL_SCROLL).build())
    val now = android.os.SystemClock.uptimeMillis()
    bookmarks.enter(currentContext!!.screen, now, true)
    assertEquals("Remember this post", bookmarks.pending(now)!!.item.text)
    // A real interaction clears the exemption and may update the remembered position.
    restorer.onUserInteraction()
    restorer.onFocusSet(automaticTop,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.MANUAL_SCROLL).build())
    bookmarks.enter(currentContext!!.screen, now, true)
    assertEquals("Automatic top", bookmarks.pending(now)!!.item.text)
  }

  @Test fun failedSearchReleasesScrollFocusExemptionAtDeadline() {
    val row = returningList()
    service.getSharedPreferences("screen-focus-test", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    row.isVisibleToUser = false
    row.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle(3300)
    val newFocus = node("Later focus", parent = currentContext!!.root)
    restorer.onFocusSet(newFocus,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.MANUAL_SCROLL).build())
    val now = android.os.SystemClock.uptimeMillis()
    bookmarks.enter(currentContext!!.screen, now, true)
    assertEquals("Later focus", bookmarks.pending(now)!!.item.text)
  }

  @Test fun windowAndContentEventsWithoutBackNeverRestore() {
    returningList(prepareBack = false)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(1100)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun forwardActivationDoesNotAuthorizeRestoration() {
    val row = returningList(prepareBack = false)
    restorer.rememberBeforeActivation(row)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    val origin = currentContext!!
    currentContext = browserContext()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    currentContext = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun statusBarFocusCancelsPendingBackEvenWithStaleApplicationRoot() {
    returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val clock = node("Clock")
    clock.packageName = "com.android.systemui"
    val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(focused).setSourceNode(clock.unwrap())
    restorer.onAccessibilityEvent(focused)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(1200)
    assertTrue(feedback.isEmpty())
  }

  @Test fun statusBarFocusStopsCorrectionAfterSuccessfulBackRestore() {
    val row = returningList()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    row.isAccessibilityFocused = false
    val clock = node("Clock")
    clock.packageName = "com.android.systemui"
    restorer.onFocusSet(clock,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build())
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle(1200)
    assertEquals(1, feedback.size)
  }

  @Test fun browserBackRestoresHeadingAfterTransientDocumentReconstruction() {
    val root = node(className = "android.widget.FrameLayout")
    root.packageName = "com.android.chrome"
    val address = node("https://example.org/a", viewId = "url_bar", parent = root)
    address.packageName = "com.android.chrome"
    val document = node("Page A", "android.webkit.WebView", parent = root)
    document.packageName = "com.android.chrome"
    val heading = node("Original heading", "android.view.View", parent = document)
    heading.packageName = "com.android.chrome"
    heading.isHeading = true
    heading.isFocusable = true
    currentContext = restorer.captureContext(root, "Chrome")
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    restorer.onFocusSet(heading, info)
    // Browsers expose the native WebView as current focus when activating an HTML link.
    restorer.rememberBeforeActivation(document)
    document.text = "Page B"
    address.text = "https://example.org/b"
    heading.text = "Other heading"
    currentContext = restorer.captureContext(root, "Chrome")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    document.text = ""
    address.text = "https://example.org/a"
    heading.text = "Loading"
    currentContext = restorer.captureContext(root, "Chrome")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    document.text = "Page A"
    heading.text = "Original heading"
    currentContext = restorer.captureContext(root, "Chrome")
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals("Original heading", feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  private fun returningBrowserHeading(): AccessibilityNodeInfoCompat {
    val root = node(className = "android.widget.FrameLayout")
    val document = node("Page A", "android.webkit.WebView", parent = root)
    val heading = node("Remembered browser heading", "android.view.View", parent = document)
    heading.isHeading = true
    heading.isFocusable = true
    currentContext = restorer.captureContext(root, "Browser")
    restorer.onFocusSet(heading,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build())
    document.text = "Page B"
    currentContext = restorer.captureContext(root, "Browser")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    document.text = "Page A"
    currentContext = restorer.captureContext(root, "Browser")
    return heading
  }

  @Test fun browserAutomaticViewportRestoreDoesNotCancelBackBeforeScreenEvent() {
    val heading = returningBrowserHeading()
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(heading.parent.unwrap())
    restorer.onAccessibilityEvent(scroll)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(heading.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun browserAutomaticViewportRestoreDoesNotCancelPendingBookmark() {
    val heading = returningBrowserHeading()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(heading.parent.unwrap())
    restorer.onAccessibilityEvent(scroll)
    settle()
    assertEquals(heading.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun browserViewportScrollFromNativeAncestorAllowsDelayedDomReconstruction() {
    val heading = returningBrowserHeading()
    val savedText = heading.text
    heading.text = "Loading"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    // Edge can report the history-entry scroll on the native browser container above WebView.
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(currentContext!!.root.unwrap())
    restorer.onAccessibilityEvent(scroll)
    heading.text = savedText
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(savedText, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun sourceLessBrowserViewportEventAllowsDelayedDomReconstruction() {
    val heading = returningBrowserHeading()
    val savedText = heading.text
    heading.text = "Loading"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    scroll.packageName = currentContext!!.root.packageName
    restorer.onAccessibilityEvent(scroll)
    heading.text = savedText
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(savedText, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun sourceLessScrollFromSystemUiCancelsBrowserReturn() {
    returningBrowserHeading()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    scroll.packageName = "com.android.systemui"
    restorer.onAccessibilityEvent(scroll)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun userInputBeforeNativeBrowserScrollCancelsReturn() {
    returningBrowserHeading()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.onUserInteraction()
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(currentContext!!.root.unwrap())
    restorer.onAccessibilityEvent(scroll)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun browserAddressUpdateDoesNotCancelBack() {
    val heading = returningBrowserHeading()
    val address = node("https://example.org/a", "android.widget.EditText", "url_bar",
      currentContext!!.root)
    address.isEditable = true
    val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    shadowOf(changed).setSourceNode(address.unwrap())
    restorer.onAccessibilityEvent(changed)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(heading.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun automaticBrowserScrollFocusDoesNotEraseHeadingBookmark() {
    val heading = returningBrowserHeading()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val automatic = node("Automatic first item", "android.view.View", parent = heading.parent)
    val scrolled = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scrolled).setSourceNode(heading.parent.unwrap())
    restorer.onAccessibilityEvent(scrolled)
    // The normal scroll interpreter can refocus after the browser restores its own viewport.
    restorer.onFocusSet(automatic,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.MANUAL_SCROLL).build())
    settle()
    assertEquals(heading.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun typingInBrowserAddressStillCancelsBack() {
    returningBrowserHeading()
    val address = node("https://example.org/a", "android.widget.EditText", "url_bar",
      currentContext!!.root)
    address.isEditable = true
    address.isFocused = true
    val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    shadowOf(changed).setSourceNode(address.unwrap())
    restorer.onAccessibilityEvent(changed)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun userBrowserScrollStillCancelsBack() {
    val heading = returningBrowserHeading()
    restorer.onUserInteraction()
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(heading.parent.unwrap())
    restorer.onAccessibilityEvent(scroll)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun browserAutomaticScrollWithoutBackCannotRestore() {
    val heading = returningBrowserHeading()
    restorer.onUserInteraction()
    val scroll = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    shadowOf(scroll).setSourceNode(heading.parent.unwrap())
    restorer.onAccessibilityEvent(scroll)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test fun asynchronousHtmlHeadingWithNativeOnlyDocumentAncestorRemainsABookmark() {
    val root = node(className = "android.widget.FrameLayout")
    root.packageName = "org.mozilla.firefox"
    val container = node("Firefox page", "android.webkit.WebView", parent = root)
    container.packageName = "org.mozilla.firefox"
    val heading = node("A web heading", "android.view.View", parent = container)
    heading.packageName = "org.mozilla.firefox"
    heading.isHeading = true
    heading.isFocusable = true
    heading.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    currentContext = restorer.captureContext(root, "Firefox")
    val page = currentContext!!
    val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()
    focusHistory.onPendingAccessibilityFocusActionOnWebElement(info,
      android.os.SystemClock.uptimeMillis(), null)
    settle(1)
    val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(focused).setSourceNode(heading.unwrap())
    restorer.onAccessibilityEvent(focused)
    assertEquals(1, bookmarks.size)
    currentContext = page.copy(screen = page.screen.copy(document = "Another document"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    currentContext = page
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(heading.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun htmlLinkWithNestedContentIsNotMistakenForDocumentContainer() {
    val root = node(className = "android.widget.FrameLayout")
    val link = node("A web link", "android.view.View", parent = root)
    link.isClickable = true
    link.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    node("Nested link content", "android.view.View", parent = link)
    currentContext = restorer.captureContext(root, "Firefox")
    restorer.onFocusSet(link,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build())
    val now = android.os.SystemClock.uptimeMillis()
    bookmarks.enter(currentContext!!.screen, now, true)
    assertEquals("A web link", bookmarks.pending(now)!!.item.text)
  }

  @Test fun activatingNativeDocumentDoesNotDiscardPendingHtmlFocusEvent() {
    val root = node(className = "android.widget.FrameLayout")
    val container = node("Page title", "android.webkit.WebView", parent = root)
    val heading = node("Pending HTML heading", "android.view.View", parent = container)
    heading.isHeading = true
    heading.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    currentContext = restorer.captureContext(root, "Firefox")
    focusHistory.onPendingAccessibilityFocusActionOnWebElement(
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
      android.os.SystemClock.uptimeMillis(), null)
    settle(1)
    focusHistory.onAccessibilityFocusAction(container,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build(),
      android.os.SystemClock.uptimeMillis(), null)
    restorer.rememberBeforeActivation(container)
    val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    shadowOf(focused).setSourceNode(heading.unwrap())
    restorer.onAccessibilityEvent(focused)
    val now = android.os.SystemClock.uptimeMillis()
    bookmarks.enter(currentContext!!.screen, now, true)
    assertEquals("Pending HTML heading", bookmarks.pending(now)!!.item.text)
  }

  @Test fun appBackButtonAuthorizesRestoreAndItsClickEventDoesNotCancelIt() {
    val row = returningList(prepareBack = false)
    val origin = currentContext!!
    currentContext = origin.copy(screen = origin.screen.copy(pane = "Comments"))
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val back = node("Back", "android.widget.Button", parent = currentContext!!.root)
    restorer.rememberBeforeActivation(back)
    val clicked = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
    shadowOf(clicked).setSourceNode(back.unwrap())
    restorer.onAccessibilityEvent(clicked)
    currentContext = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(row.text, feedback.single().failovers().single().focus()!!.target()!!.text)
  }
}
