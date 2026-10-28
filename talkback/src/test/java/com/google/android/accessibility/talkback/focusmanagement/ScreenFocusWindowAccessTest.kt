/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.FormFactorUtils
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
import org.robolectric.util.ReflectionHelpers

/** Uses the production context capture and framework window/node objects, not canned contexts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenFocusWindowAccessTest {
  private lateinit var service: TalkBackService
  private lateinit var restorer: ScreenFocusRestorer
  private val history = ScreenFocusBookmarks()
  private val feedback = ArrayList<Feedback>()
  private var activeRoot: AccessibilityNodeInfoCompat? = null
  private var activeReads = 0
  private var focusedReads = 0
  private var missingFocusedRoot = false
  private var nextWindowId = 1
  private val navigation = FocusActionInfo.builder()
    .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()

  @Before fun setUp() {
    service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    service.getSharedPreferences("window-access", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.ENABLE_PREFERENCE, true).commit()
    restorer = ScreenFocusRestorer(service,
      service.getSharedPreferences("window-access", Context.MODE_PRIVATE),
      Pipeline.FeedbackReturner { feedback.add(it); true },
      AccessibilityFocusActionHistory(service).reader, history, { true }, null,
      object : ScreenFocusRestorer.WindowAccess {
        override fun activeRoot(): AccessibilityNodeInfoCompat? { activeReads++; return activeRoot }
        override fun rootFor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
          focusedReads++
          return if (missingFocusedRoot) null else node.window?.root
        }
      })
  }

  @After fun tearDown() {
    restorer.shutdown()
    shadowOf(service.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, false)
    FormFactorUtils.initialize(service)
  }

  private fun screen(title: String, packageName: String = "example.app"):
    AccessibilityNodeInfoCompat {
    val root = node("", "android.widget.FrameLayout", packageName)
    val window = AccessibilityWindowInfo.obtain()
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
    shadowOf(window).setId(nextWindowId++)
    shadowOf(window).setTitle(title)
    shadowOf(window).setBoundsInScreen(Rect(0, 0, 100, 100))
    shadowOf(window).setRoot(root.unwrap())
    shadowOf(root.unwrap()).setAccessibilityWindowInfo(window)
    return root
  }

  private fun node(text: String, className: String = "android.widget.TextView",
    packageName: String = "example.app", parent: AccessibilityNodeInfoCompat? = null):
    AccessibilityNodeInfoCompat {
    val raw = AccessibilityNodeInfo.obtain()
    raw.text = text
    raw.className = className
    raw.packageName = packageName
    raw.isVisibleToUser = true
    raw.isImportantForAccessibility = true
    raw.setBoundsInScreen(Rect(0, 0, 100, 100))
    if (parent != null) {
      shadowOf(parent.unwrap()).addChild(raw)
      shadowOf(raw).setAccessibilityWindowInfo(parent.window!!.unwrap())
    }
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private fun event(type: Int) = restorer.onAccessibilityEvent(AccessibilityEvent.obtain(type))
  private fun settle(ms: Long = 160) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  @Test fun defaultOffDoesNotCaptureOrRestoreEvenWithScrollingEnabled() {
    val prefs = service.getSharedPreferences("window-access", Context.MODE_PRIVATE)
    prefs.edit().remove(ScreenFocusRestorer.ENABLE_PREFERENCE)
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    settle()
    activeRoot = screen("List")
    val row = node("Post", parent = activeRoot)
    restorer.onFocusSet(row, navigation)
    restorer.rememberBeforeActivation(row)
    restorer.requestBack()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(4000)
    assertEquals(0, activeReads)
    assertEquals(0, focusedReads)
    assertEquals(0, history.size)
    assertTrue(feedback.isEmpty())
  }

  @Test fun disablingCancelsPendingBackAndReenablingStartsWithEmptyHistory() {
    val origin = screen("List")
    val row = node("Post", parent = origin)
    activeRoot = origin
    restorer.onFocusSet(row, navigation)
    restorer.rememberBeforeActivation(row)
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    val reads = activeReads + focusedReads
    val prefs = service.getSharedPreferences("window-access", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(ScreenFocusRestorer.ENABLE_PREFERENCE, false).commit()
    activeRoot = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle(4000)
    assertEquals(reads, activeReads + focusedReads)
    assertEquals(0, history.size)
    assertNull(ReflectionHelpers.getField<Any?>(restorer, "cachedFocus"))
    assertTrue(feedback.isEmpty())
    prefs.edit().putBoolean(ScreenFocusRestorer.ENABLE_PREFERENCE, true).commit()
    settle()
    restorer.requestBack()
    settle()
    assertTrue(feedback.isEmpty())
    assertEquals(0, history.size)
    restorer.onFocusSet(row, navigation)
    restorer.rememberBeforeActivation(row)
    assertEquals(1, history.size)
  }

  @Test fun screenIdentityCaptureStopsAtSmallContextBudget() {
    val root = screen("List")
    repeat(ScreenFocusRestorer.MAX_CONTEXT_NODES - 1) { node("Context $it", parent = root) }
    node("Late heading", parent = root).isHeading = true
    val context = restorer.captureContext(root, "List")
    assertFalse(context.screen.context.contains("Late heading"))
    assertEquals(ScreenFocusRestorer.MAX_CONTEXT_NODES - 1, context.anchors.size)
  }

  @Test fun siblingLookupDoesNotSearchBeyondSmallBudget() {
    val root = screen("List")
    repeat(ScreenFocusRestorer.MAX_SIBLING_VISITS) { node("Earlier $it", parent = root) }
    val target = node("Target", parent = root)
    node("After", parent = root)
    val item = restorer.describe(target)
    assertEquals("Target", item.text)
    assertEquals("", item.previous)
    assertEquals("", item.next)
    assertTrue(item.ancestors.isNotEmpty())
  }

  @Test fun idleContentTransitionsHoverAndSwipesNeverReadRoots() {
    activeRoot = screen("List")
    val row = node("Post", parent = activeRoot)
    repeat(40) {
      restorer.onFocusSet(row, navigation)
      event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
      event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
      event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
      val focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
      shadowOf(focused).setSourceNode(row.unwrap())
      restorer.onAccessibilityEvent(focused)
      settle()
    }
    assertEquals(0, activeReads)
    assertEquals(0, focusedReads)
    assertEquals(0, history.size)
    assertTrue(feedback.isEmpty())
  }

  @Test fun activationDescribesFocusedSplitScreenWindowInsteadOfActiveWindow() {
    val source = screen("Origin")
    val row = node("Post", parent = source)
    activeRoot = screen("Other split-screen app", "example.other")
    restorer.onFocusSet(row, navigation)
    restorer.rememberBeforeActivation(row)
    assertEquals(0, activeReads)
    assertEquals(1, focusedReads)
    assertEquals("Origin", history.activeScreen!!.title)
    assertEquals(1, history.size)
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    activeRoot = source
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals("Post", feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun backCapturesLatestFocusAndReadsReturningRootOncePerAttempt() {
    val list = screen("List")
    val row = node("Post", parent = list)
    activeRoot = list
    restorer.onFocusSet(row, navigation)
    restorer.requestBack()
    assertEquals(1, focusedReads)
    assertEquals(0, activeReads)
    // Navigate away, then explicitly Back to the saved list.
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    activeRoot = list
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val before = activeReads
    settle()
    assertEquals(before + 1, activeReads)
    assertEquals(1, feedback.size)
  }

  @Test fun refreshedRecycledFocusCannotBecomeANewBookmark() {
    activeRoot = screen("List")
    val row = node("Original", parent = activeRoot)
    restorer.onFocusSet(row, navigation)
    row.text = "Replacement"
    // Robolectric refresh does not reload obtained copies from an app's virtual tree. Update
    // that copy to model the contents a real refresh would return for the recycled source ID.
    val cached = ReflectionHelpers.getField<Any>(restorer, "cachedFocus")
    ReflectionHelpers.getField<AccessibilityNodeInfoCompat>(cached, "node").text = "Replacement"
    restorer.requestBack()
    assertEquals(0, focusedReads)
    assertEquals(0, history.size)
  }

  @Test fun hoverCancellationAndDeadlineDoNotReadRoots() {
    activeRoot = screen("Details")
    restorer.requestBack()
    val before = activeReads
    event(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(4000)
    assertEquals(before, activeReads)
    restorer.requestBack()
    settle(4000)
    assertNull(ReflectionHelpers.getField<Any?>(restorer, "observedContext"))
    val expired = activeReads
    repeat(10) { event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); settle() }
    assertEquals(expired, activeReads)
  }

  @Test fun systemWindowDuringBackCancelsWithoutSearchingApplication() {
    val origin = screen("List")
    val row = node("Post", parent = origin)
    activeRoot = origin
    restorer.rememberBeforeActivation(row)
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    val system = screen("Status bar", "com.android.systemui")
    shadowOf(system.window!!.unwrap()).setType(AccessibilityWindowInfo.TYPE_SYSTEM)
    activeRoot = system
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    activeRoot = origin
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
  }

  @Test @Config(qualifiers = "cs") fun localizedNavigateUpAuthorizesBack() {
    val origin = screen("List")
    val row = node("Post", parent = origin)
    activeRoot = origin
    restorer.rememberBeforeActivation(row)
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    val up = node(service.getString(androidx.appcompat.R.string.abc_action_bar_up_description),
      "android.widget.Button", parent = activeRoot)
    assertNotEquals("Navigate up", up.text.toString())
    restorer.rememberBeforeActivation(up)
    activeRoot = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
  }

  @Test fun wearNeverReadsWindowsEvenWithScrollingEnabled() {
    shadowOf(service.packageManager).setSystemFeature(PackageManager.FEATURE_WATCH, true)
    FormFactorUtils.initialize(service)
    service.getSharedPreferences("window-access", Context.MODE_PRIVATE).edit()
      .putBoolean(ScreenFocusRestorer.SCROLL_PREFERENCE, true).commit()
    activeRoot = screen("List")
    val row = node("Post", parent = activeRoot)
    restorer.onFocusSet(row, navigation)
    restorer.rememberBeforeActivation(row)
    restorer.requestBack()
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle(4000)
    assertEquals(0, activeReads)
    assertEquals(0, focusedReads)
    assertEquals(0, history.size)
    assertTrue(feedback.isEmpty())
  }

  @Test fun recreatedWindowUsesContentBookmark() {
    activeRoot = screen("List")
    restorer.rememberBeforeActivation(node("Post", parent = activeRoot))
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    activeRoot = screen("List")
    node("Post", parent = activeRoot)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(1, feedback.size)
  }

  @Test fun sameWindowBrowserReturnWaitsForDelayedDocumentWithoutExtraRootReads() {
    val browser = screen("Browser", "example.browser")
    activeRoot = browser
    val document = node("Page A", "android.webkit.WebView", "example.browser", browser)
    val heading = node("Heading A", "android.view.View", "example.browser", document)
    heading.isHeading = true
    heading.isFocusable = true
    restorer.onFocusSet(heading, navigation)
    restorer.rememberBeforeActivation(document)
    document.text = "Page B"
    heading.text = "Heading B"
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    document.text = "Page A"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    val before = activeReads
    heading.text = "Heading A"
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(before + 1, activeReads)
    assertEquals("Heading A", feedback.single().failovers().single().focus()!!.target()!!.text)
  }

  @Test fun automaticInitialFocusDoesNotReplaceCachedUserPosition() {
    activeRoot = screen("List")
    val row = node("Post", parent = activeRoot)
    restorer.onFocusSet(row, navigation)
    restorer.onFocusSet(node("Toolbar", parent = activeRoot),
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE).build())
    restorer.requestBack()
    val now = android.os.SystemClock.uptimeMillis()
    history.enter(history.activeScreen, now, true)
    assertEquals("Post", history.pending(now)!!.item.text)
    assertEquals(0, activeReads)
    assertEquals(1, focusedReads)
  }

  @Test fun transientMissingRootWaitsForContentButDoesNotExtendDeadline() {
    val origin = screen("List")
    activeRoot = origin
    restorer.rememberBeforeActivation(node("Post", parent = origin))
    activeRoot = screen("Details")
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    activeRoot = null
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertTrue(feedback.isEmpty())
    activeRoot = origin
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(1, feedback.size)
    settle(3200)
    val before = activeReads
    activeRoot = null
    event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    settle()
    assertEquals(before, activeReads)
  }

  @Test fun backFromKeyboardDoesNotArmSupplementaryRestoration() {
    activeRoot = screen("List")
    restorer.rememberBeforeActivation(node("Post", parent = activeRoot))
    val origin = activeRoot
    activeRoot = screen("Keyboard", "example.keyboard")
    shadowOf(activeRoot!!.window!!.unwrap()).setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    restorer.requestBack()
    val before = activeReads
    activeRoot = origin
    event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    settle()
    assertEquals(before, activeReads)
    assertTrue(feedback.isEmpty())
  }

  @Test fun missingVirtualWindowRootFallsBackOnlyToMatchingActiveWindow() {
    activeRoot = screen("List")
    val row = node("Post", parent = activeRoot)
    missingFocusedRoot = true
    restorer.rememberBeforeActivation(row)
    assertEquals(1, history.size)
    assertEquals(1, activeReads)
    assertEquals(1, focusedReads)
  }

  @Test fun missingSplitScreenRootCannotSaveItemAgainstOtherActiveWindow() {
    val origin = screen("List")
    val row = node("Post", parent = origin)
    activeRoot = screen("Other app", "example.other")
    missingFocusedRoot = true
    restorer.rememberBeforeActivation(row)
    assertEquals(0, history.size)
    assertEquals(1, activeReads)
    assertEquals(1, focusedReads)
  }

  @Test fun nativeDocumentActivationCannotCaptureFocusFromAnotherWindow() {
    val origin = screen("List")
    restorer.onFocusSet(node("Post", parent = origin), navigation)
    activeRoot = screen("Browser", "example.browser")
    val document = node("Page", "android.webkit.WebView", "example.browser", activeRoot)
    restorer.rememberBeforeActivation(document)
    assertEquals(0, history.size)
    assertEquals(0, activeReads)
    assertEquals(1, focusedReads)
  }
}
