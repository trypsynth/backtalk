/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.UserInterface.UserInputEventListener
import com.google.android.accessibility.talkback.focusmanagement.record.AccessibilityFocusActionHistory
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.pause.PauseController
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.Role
import com.google.android.accessibility.utils.WebInterfaceUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.security.MessageDigest

/**
 * Complements window history with content bookmarks. Runs after normal initial-focus processing,
 * so a returning screen can replace automatic toolbar focus without changing dialog/IME policy.
 * Bookmarks retain values only. The latest user focus is held until activation or Back, when
 * its identity is checked and described; ordinary focus and content events never scan screens.
 */
class ScreenFocusRestorer internal constructor(
  private val service: TalkBackService,
  private val prefs: SharedPreferences,
  private val pipeline: Pipeline.FeedbackReturner,
  private val focusHistory: AccessibilityFocusActionHistory.Reader,
  private val history: ScreenFocusBookmarks,
  private val available: () -> Boolean,
  private val readContext: (() -> Context?)?,
  private val windows: WindowAccess = ServiceWindowAccess(service),
) : UserInputEventListener {
  constructor(service: TalkBackService, prefs: SharedPreferences,
    pipeline: Pipeline.FeedbackReturner, focusHistory: AccessibilityFocusActionHistory.Reader) :
    this(service, prefs, pipeline, focusHistory, ScreenFocusBookmarks(),
      { TalkBackService.isServiceActive() && !service.actorState.continuousRead.isActive }, null)
  companion object {
    const val ENABLE_PREFERENCE = "pref_restore_focus_on_back"
    const val SCROLL_PREFERENCE = "pref_scroll_to_restore_previous_focus"
    private const val TAG = "ScreenFocusRestorer"
    private const val SETTLE_DELAY_MS = 120L
    private const val MAX_SEARCH_NODES = 1000
    // Capture still precedes navigation so it cannot accidentally describe the destination.
    // Keep the same small identity budget when recognizing that screen on return.
    internal const val MAX_CONTEXT_NODES = 32
    internal const val MAX_SIBLING_VISITS = 24
    // Native address controls already recognized by WebInterfaceUtils; search only the skeleton,
    // never an entire document just because its browser toolbar is temporarily hidden.
    private val ADDRESS_IDS = setOf("url_bar", "url", "url_bar_title", "url_edit_text",
      "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view",
      "location_bar_edit_text", "url_field")
  }

  private val handler = Handler(Looper.getMainLooper())
  private var transitionQueued = false
  private var restoring = false
  private var scrollNode: AccessibilityNodeInfoCompat? = null
  private var scrollTime = 0L
  private var triedPosition = false
  private var waitingForScroll = false
  private var stopped = false
  private var generation = 0L
  private var observedContext: Context? = null
  private var ordinaryWindow = false
  private var cachedFocus: CachedFocus? = null
  private var awaitingWebFocus = false
  private var awaitingWebScreen: RememberedScreen? = null
  private var activationTime = 0L
  private var lastWindowId: Int? = null
  private var lastRecordedFocusTime = Long.MIN_VALUE
  private var backRequestedAt: Long? = null
  private var backFrom: RememberedScreen? = null
  private var backWindowId: Int? = null
  private var activationOrigin: RememberedScreen? = null
  private var retryAt = Long.MAX_VALUE
  private val retry = Runnable { retryAt = Long.MAX_VALUE; attemptRestore() }
  private val deadline = Runnable { onUserInteraction() }
  private val releaseWebFocus = Runnable { awaitingWebFocus = false; awaitingWebScreen = null }

  internal data class Context(
    val root: AccessibilityNodeInfoCompat,
    val screen: RememberedScreen,
    val anchors: Set<String>,
  )

  /** Boundary for framework reads; tests feed actual trees through the production capture path. */
  internal interface WindowAccess {
    fun activeRoot(): AccessibilityNodeInfoCompat?
    fun rootFor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat?
  }

  private class ServiceWindowAccess(private val service: TalkBackService) : WindowAccess {
    override fun activeRoot() = service.rootInActiveWindow?.let(AccessibilityNodeInfoCompat::wrap)
    override fun rootFor(node: AccessibilityNodeInfoCompat) = node.window?.root
  }

  private data class FocusIdentity(val packageName: String, val windowId: Int,
    val uniqueId: String, val viewId: String, val className: String, val role: Int,
    val text: String, val description: String)

  private data class CachedFocus(val node: AccessibilityNodeInfoCompat, val identity: FocusIdentity)

  private fun identity(node: AccessibilityNodeInfoCompat) = FocusIdentity(
    node.packageName?.toString().orEmpty(), node.windowId, node.uniqueId.orEmpty(),
    node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(), Role.getRole(node),
    boundedIdentity(node.text?.toString().orEmpty()),
    boundedIdentity(node.contentDescription?.toString().orEmpty()))

  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == ENABLE_PREFERENCE) {
      onUserInteraction()
      cachedFocus = null
      activationOrigin = null
      history.clear()
    }
  }

  init {
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
  }

  private fun enabled() = !stopped && !FormFactorUtils.isAndroidWear() &&
    !FormFactorUtils.isAndroidTv() && prefs.getBoolean(ENABLE_PREFERENCE, false)

  private fun authorized(now: Long = SystemClock.uptimeMillis()): Boolean =
    history.pending(now) != null || backRequestedAt?.let {
      now >= it && now - it < ScreenFocusBookmarks.RESTORE_TIMEOUT_MS
    } == true

  override fun touchInteractionState(active: Boolean) {
    if (active) onUserInteraction()
  }

  fun onKeyEvent(event: KeyEvent) {
    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
      requestBack()
      return
    }
    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_POWER &&
      event.keyCode != KeyEvent.KEYCODE_WAKEUP && event.keyCode != KeyEvent.KEYCODE_SLEEP) {
      onUserInteraction()
    }
  }

  fun onUserInteraction() {
    generation++
    backRequestedAt = null
    backFrom = null
    backWindowId = null
    observedContext = null
    awaitingWebFocus = false
    awaitingWebScreen = null
    history.cancel()
    handler.removeCallbacks(retry)
    handler.removeCallbacks(deadline)
    handler.removeCallbacks(releaseWebFocus)
    retryAt = Long.MAX_VALUE
    transitionQueued = false
    scrollNode = null
    waitingForScroll = false
  }

  /** Also called synchronously before the app's event for a successful focus action arrives. */
  fun onFocusSet(node: AccessibilityNodeInfoCompat, info: FocusActionInfo,
    actionTime: Long = SystemClock.uptimeMillis()) {
    if (!enabled() || restoring) return
    if (node.packageName?.toString() == "com.android.systemui") {
      onUserInteraction()
      cachedFocus = null
      return
    }
    if (info.sourceAction == FocusActionInfo.SCREEN_STATE_CHANGE) {
      // Normal initial focus may run again after our successful restore during the first load.
      // Keep the original bounded attempt alive to replace that late automatic toolbar focus.
      if (history.pending(SystemClock.uptimeMillis()) != null) schedule()
      return
    }
    if (!isUserFocus(info)) return
    val observed = observedContext
    if (authorized() && observed != null && node.packageName == observed.root.packageName &&
      node.windowId == observed.root.windowId && node != observed.root &&
      !AccessibilityNodeInfoUtils.hasAncestor(node, observed.root)) return
    // Direct show/scroll actions can be followed by the manual-scroll interpreter's focus action.
    // Real input clears scrollNode first, so this exemption cannot override a user's gesture.
    if (info.sourceAction == FocusActionInfo.MANUAL_SCROLL &&
      (scrollNode != null || isReturningBrowserContent(node))) {
      lastRecordedFocusTime = maxOf(lastRecordedFocusTime, actionTime)
      if (scrollNode == null) schedule()
      return
    }
    val finishActivation = awaitingWebFocus && actionTime <= activationTime &&
      (node.isHeading || WebInterfaceUtils.supportsWebActions(node))
    val expectedScreen = awaitingWebScreen
    onUserInteraction()
    if (!node.isPassword && !node.isEditable && Role.getRole(node) != Role.ROLE_WEB_VIEW) {
      cachedFocus = CachedFocus(AccessibilityNodeInfoCompat.obtain(node), identity(node))
      lastRecordedFocusTime = maxOf(lastRecordedFocusTime, actionTime)
      activationOrigin = null
      // Firefox may resolve a pending HTML navigation only after native activation. This is
      // still part of that activation, rather than work performed on every ordinary swipe.
      if (finishActivation) {
        activationOrigin = recordPosition(node, expectedScreen)?.screen
        cachedFocus = null
      }
    } else {
      cachedFocus = null
    }
  }

  /** Capture initial/app-assigned focus too when the user activates it without first swiping. */
  fun rememberBeforeActivation(node: AccessibilityNodeInfoCompat) {
    if (!enabled()) return
    if (isBackControl(node)) {
      requestBack()
      return
    }
    val document = isDocument(node) && (Role.getRole(node) == Role.ROLE_WEB_VIEW ||
      (!node.isHeading && !node.isClickable && node.childCount > 0))
    val cached = cachedFocus
    val recorded = if (document) {
      if (cached != null && cached.identity.packageName == node.packageName?.toString() &&
        cached.identity.windowId == node.windowId) recordCachedPosition() else null
    } else recordPosition(node)
    if (recorded == null && !document) return
    val origin = recorded ?: context(node)
    onUserInteraction()
    if (recorded != null) focusHistory.lastFocusActionRecord?.let { record ->
      if (record.focusedNode == node) {
        lastRecordedFocusTime = maxOf(lastRecordedFocusTime, record.actionTime)
      }
    }
    activationOrigin = origin?.screen
    awaitingWebFocus = document && recorded == null && origin != null
    awaitingWebScreen = if (awaitingWebFocus) origin?.screen else null
    activationTime = SystemClock.uptimeMillis()
    if (awaitingWebFocus) handler.postDelayed(releaseWebFocus, ScreenFocusBookmarks.RESTORE_TIMEOUT_MS)
    cachedFocus = null
  }

  private fun isBackControl(node: AccessibilityNodeInfoCompat): Boolean {
    if (Role.getRole(node) != Role.ROLE_BUTTON && Role.getRole(node) != Role.ROLE_IMAGE_BUTTON) return false
    return node.viewIdResourceName == "android:id/home" ||
      node.viewIdResourceName?.substringAfterLast('/') in setOf("back", "back_button", "navigate_up") ||
      label(node) == service.getString(androidx.appcompat.R.string.abc_action_bar_up_description)
  }

  private fun recordCachedPosition(): Context? {
    val cached = cachedFocus ?: return null
    // A framework handle may now represent a different recycled row. Never describe or save it
    // against the current screen until the cheap identity snapshot has survived a refresh.
    if (!cached.node.refresh() || identity(cached.node) != cached.identity) return null
    return recordPosition(cached.node)
  }

  private fun recordPosition(node: AccessibilityNodeInfoCompat,
    expectedScreen: RememberedScreen? = null): Context? {
    if (!enabled()) return null
    val context = context(node) ?: return null
    if (expectedScreen != null && context.screen != expectedScreen) return null
    if (node.windowId != context.root.windowId || node.packageName != context.root.packageName) return null
    if (!node.refresh() || (node != context.root &&
        !AccessibilityNodeInfoUtils.hasAncestor(node, context.root))) {
      return null
    }
    if (node.isPassword || node.isEditable ||
      (isDocument(node) && (Role.getRole(node) == Role.ROLE_WEB_VIEW ||
        (!node.isHeading && !node.isClickable && node.childCount > 0)))) {
      return null
    }
    val item = describe(node)
    if (!item.hasContent && item.uniqueId.isEmpty()) return null
    history.enter(context.screen, SystemClock.uptimeMillis(), allowRestore = false)
    history.remember(context.screen, context.anchors, item)
    lastWindowId = context.root.windowId
    return context
  }

  /** Only explicit Back (gesture, key, or app Back button) authorizes a restoration attempt. */
  fun requestBack() {
    if (!enabled()) return
    onUserInteraction()
    val departing = recordCachedPosition() ?: context()
    cachedFocus = null
    awaitingWebFocus = false
    if (departing == null && ordinaryWindow) return
    backFrom = if (departing?.screen == activationOrigin) null else departing?.screen
    backWindowId = if (backFrom != null) departing?.root?.windowId else null
    activationOrigin = null
    backRequestedAt = SystemClock.uptimeMillis()
    observedContext = departing
    // Failed searches/actions can cancel history before a retry is posted. Release every
    // temporary node and context at the original deadline even in those failure paths.
    handler.postDelayed(deadline, ScreenFocusBookmarks.RESTORE_TIMEOUT_MS)
    schedule()
  }

  private fun isUserFocus(info: FocusActionInfo): Boolean = when (info.sourceAction) {
    FocusActionInfo.TOUCH_EXPLORATION, FocusActionInfo.LOGICAL_NAVIGATION,
    FocusActionInfo.MANUAL_SCROLL, FocusActionInfo.KEYBOARD_SHORTCUT_REFOCUS -> true
    else -> false
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!enabled()) return
    when (event.eventType) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
      AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> onUserInteraction()
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
        val source = event.source?.let(AccessibilityNodeInfoCompat::wrap)
        // Browsers update their address/title controls while reconstructing a history entry.
        // These notifications are not typing. Touch, keys and navigation cancel separately;
        // an editor receiving input still cancels immediately.
        if (source != null && !source.isFocused && isReturningBrowserContent(source, address = true)) {
          schedule()
        } else onUserInteraction()
      }
      AccessibilityEvent.TYPE_VIEW_CLICKED -> {
        val source = event.source?.let(AccessibilityNodeInfoCompat::wrap)
        // This is the event produced by the Back button whose activation armed the request.
        if (!authorized() || source == null || !isBackControl(source)) onUserInteraction()
      }
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
        val focused = event.source?.let(AccessibilityNodeInfoCompat::wrap)
        if (focused?.packageName?.toString() == "com.android.systemui") {
          onUserInteraction()
          cachedFocus = null
          return
        }
        // Firefox's HTML navigation is asynchronous and bypasses the synchronous focus-set hook.
        val record = focusHistory.matchFocusActionRecordFromEvent(event) ?: run {
          // Apps can assign focus without going through our focus actor. Genuine touch/key/
          // navigation input already cancels this bounded attempt through the input hooks.
          if (history.pending(SystemClock.uptimeMillis()) != null) schedule()
          return
        }
        // Native actions were recorded synchronously. Their delayed events must not cancel a
        // return attempt or save the old item against a destination in the same window.
        if (record.actionTime <= lastRecordedFocusTime) return
        val source = event.source ?: return
        onFocusSet(AccessibilityNodeInfoCompat.wrap(source), record.extraInfo, record.actionTime)
      }
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          val navigationChanges = AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED or AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_TITLE
          if (event.windowChanges and navigationChanges != 0) queueTransition() else schedule()
        } else schedule()
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> queueTransition()
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule()
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
        val source = event.source?.let(AccessibilityNodeInfoCompat::wrap)
        if (scrollNode != null && source != null &&
          (source == scrollNode || AccessibilityNodeInfoUtils.hasAncestor(scrollNode, source)) &&
          SystemClock.uptimeMillis() - scrollTime < 1000L) {
          waitingForScroll = false
          schedule()
        } else if (isReturningBrowserScroll(event, source)) {
          // Browser Back restores the document's viewport itself, even with our optional scroll
          // setting disabled. Do not lose the Back authorization to that automatic scroll event.
          // A user's scroll gesture/key already cancels through the input hooks before this event.
          schedule()
        } else {
          // App/manual scrolls must not be mistaken for restoration scrolls.
          onUserInteraction()
        }
      }
      else -> Unit // Other event types remain with the existing focus and announcement policies.
    }
  }

  private fun isReturningBrowserContent(node: AccessibilityNodeInfoCompat,
    address: Boolean = false): Boolean {
    val active = returningBrowserContext() ?: return false
    if (node.packageName != active.root.packageName || node.windowId != active.root.windowId) return false
    if (address && node.viewIdResourceName?.substringAfterLast('/') in ADDRESS_IDS) return true
    // Edge reports automatic history-entry scrolling on native containers above the WebView,
    // as well as on HTML nodes. The existing Back grant and document identity scope this to the
    // browser window; actual user input has already revoked that grant through the input hooks.
    if (!address && (node == active.root || AccessibilityNodeInfoUtils.hasAncestor(node, active.root))) {
      return true
    }
    var ancestor: AccessibilityNodeInfoCompat? = node
    repeat(20) {
      val current = ancestor ?: return false
      if (isDocument(current)) return true
      ancestor = current.parent
    }
    return false
  }

  private fun isReturningBrowserScroll(event: AccessibilityEvent,
    source: AccessibilityNodeInfoCompat?): Boolean {
    if (source != null) return isReturningBrowserContent(source)
    val active = returningBrowserContext() ?: return false
    // A browser may clear the source while rebuilding its virtual tree. Window and package must
    // still agree; source-less events from System UI or another app continue to cancel the attempt.
    return event.packageName == active.root.packageName && event.windowId == active.root.windowId
  }

  private fun returningBrowserContext(): Context? {
    val now = SystemClock.uptimeMillis()
    if (history.pending(now) == null &&
      backRequestedAt?.let { now - it < ScreenFocusBookmarks.RESTORE_TIMEOUT_MS } != true) return null
    val active = observedContext ?: return null
    if (active.screen.document.isEmpty() && (backFrom?.packageName != active.screen.packageName ||
        backFrom?.document.isNullOrEmpty())) return null
    return active
  }

  private fun queueTransition() {
    generation++
    if (!authorized()) {
      cachedFocus = null
      awaitingWebFocus = false
      awaitingWebScreen = null
      observedContext = null
      return
    }
    transitionQueued = true
    // Window/title events may repeat throughout reconstruction. Only a changed screen or
    // recreated window starts a new attempt; duplicate events cannot reset its deadline or input veto.
    // Only a Back attempt pays for observing a window; all other transitions are bookkeeping.
    schedule()
  }

  private fun observeScreen(context: Context?, now: Long) {
    val requestedAt = backRequestedAt
    val validBack = requestedAt != null && now - requestedAt < ScreenFocusBookmarks.RESTORE_TIMEOUT_MS
    if (!validBack) backRequestedAt = null
    val returning = validBack && context != null &&
      (context.screen != backFrom || context.root.windowId != backWindowId) &&
      history.contains(context.screen)
    val changed = context?.screen != history.activeScreen ||
      (transitionQueued && lastWindowId != null && context?.root?.windowId != lastWindowId)
    history.enter(context?.screen, if (returning) requestedAt else now,
      changed || returning, allowRestore = returning)
    if (returning) backRequestedAt = null
    lastWindowId = context?.root?.windowId
    if (changed) {
      triedPosition = false
      scrollNode = null
      waitingForScroll = false
    }
  }

  private fun schedule(delay: Long = SETTLE_DELAY_MS) {
    if (!enabled() || !authorized()) return
    // Do not debounce forever when pages continuously update.
    val at = SystemClock.uptimeMillis() + delay
    if (at >= retryAt) return
    handler.removeCallbacks(retry)
    retryAt = at
    handler.postDelayed(retry, delay)
  }

  private fun attemptRestore() {
    if (!enabled() || PauseController.isPaused() || !available()) {
      onUserInteraction()
      return
    }
    val now = SystemClock.uptimeMillis()
    if (!authorized(now)) {
      onUserInteraction()
      return
    }
    val attemptGeneration = generation
    val context = context()
    if (context == null) {
      // A browser may temporarily have no root while reconstructing a history entry. Keep
      // the original deadline and wait for content events, but never cross into an ordinary
      // dialog, keyboard, or system window with this supplementary restoration path.
      if (ordinaryWindow) onUserInteraction()
      return
    }
    observedContext = context
    observeScreen(context, now)
    transitionQueued = false
    val bookmark = history.pending(now) ?: run {
      scrollNode = null
      waitingForScroll = false
      backRequestedAt?.let { schedule((ScreenFocusBookmarks.RESTORE_TIMEOUT_MS - (now - it)).coerceAtLeast(0)) }
      return
    }
    val nodes = walk(context.root, MAX_SEARCH_NODES)
    if (nodes.any { it.isFocused && it.isEditable }) {
      history.cancel()
      scrollNode = null
      waitingForScroll = false
      return
    }
    if (waitingForScroll && now - scrollTime >= SETTLE_DELAY_MS * 2) waitingForScroll = false
    val candidates = nodes.filter { !it.isPassword && !it.isEditable &&
      it.className?.toString().orEmpty() == bookmark.item.className &&
      Role.getRole(it) == bookmark.item.role &&
      (it.uniqueId?.let { id -> id.isNotEmpty() && id == bookmark.item.uniqueId } == true ||
        (bookmark.item.hasContent && itemText(it) == bookmark.item.text)) }
    val items = candidates.map(::describe)
    val viewport = nodes.filter { visibleOnScreen(it, context.root) &&
      it.parent?.let(::containerId) == bookmark.item.container }
      .map { RememberedItem(text = itemText(it), row = it.collectionItemInfo?.rowIndex ?: -1,
        column = it.collectionItemInfo?.columnIndex ?: -1) }
    val match = history.find(bookmark, items)
    var matchedOffscreen = false
    if (match != null) {
      val node = candidates[match]
      if (node.refresh() && bookmark.item.matchScore(describe(node)) > 0 &&
        (bookmark.item.uniqueId.isNotEmpty() || bookmark.screen.document.isNotEmpty() ||
          bookmark.anchors.isEmpty() || history.mayScroll(bookmark, context.anchors)) &&
        node.windowId == context.root.windowId &&
        AccessibilityNodeInfoUtils.hasAncestor(node, context.root)) {
        if (history.pending(SystemClock.uptimeMillis()) == null ||
          generation != attemptGeneration) {
          history.cancel()
          return
        }
        matchedOffscreen = !visibleOnScreen(node, context.root)
        if (visibleOnScreen(node, context.root) &&
          AccessibilityNodeInfoUtils.shouldFocusNode(node)) {
          restoring = true
          try {
            val info = FocusActionInfo.builder()
              .setSourceAction(FocusActionInfo.SCREEN_STATE_CHANGE)
              .setInitialFocusType(FocusActionInfo.RESTORED_LAST_FOCUS)
              .build()
            if (node.isAccessibilityFocused || pipeline.returnFeedback(
                Performance.EVENT_ID_UNTRACKED, Feedback.focus(node, info))) {
              scrollNode = null
              schedule(history.remaining(SystemClock.uptimeMillis()))
              LogUtils.d(TAG, "Restored remembered item in %s window %d",
                context.screen.packageName, node.windowId)
              return
            }
          } finally {
            restoring = false
          }
        } else if (matchedOffscreen && scrollEnabled() && history.mayScroll(bookmark, context.anchors) &&
          supports(node, AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id) &&
          !waitingForScroll && history.beginScroll(now, viewport)) {
          if (requestScroll(node, AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id, null,
              context, attemptGeneration)) return
          history.cancel()
          return
        }
      }
    }
    val rows = viewport.filter { it.row >= 0 }
    val savedPositionOutsideViewport = bookmark.item.row >= 0 && rows.isNotEmpty() &&
      (bookmark.item.row < rows.minOf { it.row } || bookmark.item.row > rows.maxOf { it.row })
    // A missing/changed label does not prove the item is offscreen. Apps can rebuild a visible
    // row after marking an item read. Position is only evidence against scrolling,
    // never enough evidence to focus an item. A visible candidate also rules out moving the list
    // after focus fails, or while duplicate labels/context prevent selecting a safe target.
    val visibleCandidate = candidates.any { visibleOnScreen(it, context.root) }
    if (scrollEnabled() && !visibleCandidate && (matchedOffscreen || savedPositionOutsideViewport) &&
      !waitingForScroll && history.mayScroll(bookmark, context.anchors)) {
      val containers = nodes.filter { containerId(it) == bookmark.item.container &&
        bookmark.item.container.isNotEmpty() && (it.isScrollable || it.collectionInfo != null) }
      val container = containers.singleOrNull()
      if (container != null && history.beginScroll(now, viewport)) {
        if (!triedPosition && bookmark.item.row >= 0 && supports(container,
            AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id)) {
          triedPosition = true
          val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, bookmark.item.row)
            if (bookmark.item.column >= 0) {
              putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, bookmark.item.column)
            }
          }
          if (requestScroll(container, AccessibilityActionCompat.ACTION_SCROLL_TO_POSITION.id,
              args, context, attemptGeneration)) return
          // A rejected action terminates this attempt instead of issuing a second action against
          // the same viewport (which would also bypass the ten-action budget).
          history.cancel()
          return
        }
        val backwards = bookmark.item.row >= 0 && rows.isNotEmpty() &&
          bookmark.item.row < rows.minOf { it.row }
        val action = if (backwards) AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD
          else AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD
        if (requestScroll(container, action, null, context, attemptGeneration)) return
        history.cancel()
      }
    }
    // Content events trigger retries; this one deadline callback releases pending state even if
    // the app never sends another event. Normal initial focus already remains available.
    val finishedAt = SystemClock.uptimeMillis()
    if (history.pending(finishedAt) != null) schedule(history.remaining(finishedAt))
  }

  private fun scrollEnabled(): Boolean = prefs.getBoolean(SCROLL_PREFERENCE, false)

  private fun visibleOnScreen(node: AccessibilityNodeInfoCompat,
    root: AccessibilityNodeInfoCompat): Boolean {
    if (!node.isVisibleToUser || !AccessibilityNodeInfoUtils.isVisible(node)) return false
    val bounds = Rect()
    val viewport = Rect()
    node.getBoundsInScreen(bounds)
    val window = root.window
    if (window != null) window.getBoundsInScreen(viewport) else root.getBoundsInScreen(viewport)
    return !bounds.isEmpty && Rect.intersects(bounds, viewport)
  }

  private fun supports(node: AccessibilityNodeInfoCompat, action: Int): Boolean =
    node.actionList.any { it.id == action }

  private fun requestScroll(node: AccessibilityNodeInfoCompat, action: Int, args: Bundle?,
    expected: Context, attemptGeneration: Long): Boolean {
    if (history.pending(SystemClock.uptimeMillis()) == null) return false
    if (generation != attemptGeneration || !node.refresh() ||
      (node != expected.root && !AccessibilityNodeInfoUtils.hasAncestor(node, expected.root))) {
      history.cancel()
      return false
    }
    if (!supports(node, action)) return false
    if (history.pending(SystemClock.uptimeMillis()) == null) return false
    scrollNode = AccessibilityNodeInfoCompat.obtain(node)
    scrollTime = SystemClock.uptimeMillis()
    waitingForScroll = true
    val success = pipeline.returnFeedback(Performance.EVENT_ID_UNTRACKED,
      Feedback.nodeAction(node, action, args))
    if (!success) {
      waitingForScroll = false
      scrollNode = null
    } else {
      // Some apps perform the action without a scroll event. Re-read the tree, then stop if it
      // has not progressed. Never focus a guessed row after requesting a positional scroll.
      schedule(SETTLE_DELAY_MS * 2)
    }
    return success
  }

  private fun context(node: AccessibilityNodeInfoCompat? = null): Context? {
    ordinaryWindow = false
    if (!enabled()) return null
    if (readContext != null) return readContext.invoke()
    val root = (if (node != null) windows.rootFor(node) ?: windows.activeRoot()
      else windows.activeRoot()) ?: return null
    // Some browser virtual nodes cannot resolve their window root. The active root is a safe
    // fallback only when it belongs to that exact window and package, including split screen.
    if (node != null && (root.windowId != node.windowId || root.packageName != node.packageName)) return null
    val window = root.window ?: return null
    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
      ordinaryWindow = true
      return null
    }
    val packageName = root.packageName?.toString().orEmpty()
    if (packageName.isEmpty() || packageName == service.packageName ||
      packageName == "com.android.systemui") {
      ordinaryWindow = true
      return null
    }
    return captureContext(root, window.title?.toString().orEmpty())
  }

  internal fun captureContext(root: AccessibilityNodeInfoCompat, title: String): Context {
    // Stop at content containers: identity must not depend on which recycled rows are visible.
    val skeleton = walk(root, MAX_CONTEXT_NODES, stopAtContent = true)
    val documents = skeleton.filter(::isDocument)
    val documentTitle = documents.firstOrNull()?.let(::label).orEmpty()
    val url = skeleton.firstOrNull {
      it.viewIdResourceName?.substringAfterLast('/') in ADDRESS_IDS
    }?.takeUnless { it.isPassword }?.text?.toString().orEmpty().let(::boundedIdentity)
    val document = if (documentTitle.isNotEmpty() || url.isNotEmpty()) "$documentTitle|$url" else ""
    val pane = skeleton.mapNotNull { it.paneTitle?.toString()?.takeIf(String::isNotEmpty) }
      .distinct().joinToString("|")
    val structure = skeleton.filter { it.collectionInfo != null || it.isScrollable ||
      isDocument(it) }.map(::containerId).distinct().sorted()
    val headings = skeleton.filter { node -> node.isHeading ||
      node.viewIdResourceName?.substringAfterLast('/') in
        setOf("title", "action_bar_title", "toolbar_title") }
      .map(::label).filter(String::isNotEmpty).distinct()
    val anchors = skeleton.mapNotNull { node ->
      val text = label(node)
      if (text.isNotEmpty() && !node.isEditable && !node.isPassword) {
        "${containerId(node)}:$text"
      } else null
    }.toSet()
    return Context(root, RememberedScreen(root.packageName?.toString().orEmpty(), title,
      pane, document, structure, headings), anchors)
  }

  private fun walk(root: AccessibilityNodeInfoCompat, limit: Int,
    stopAtContent: Boolean = false): List<AccessibilityNodeInfoCompat> {
    val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    val result = ArrayList<AccessibilityNodeInfoCompat>()
    queue.add(root)
    while (queue.isNotEmpty() && result.size < limit) {
      val node = queue.removeFirst()
      if (!visited.add(node)) continue
      result.add(node)
      if (node.isPassword || node.isEditable) continue
      if (stopAtContent && (node.collectionInfo != null || node.isScrollable ||
          isDocument(node))) continue
      for (i in 0 until minOf(node.childCount, limit - result.size - queue.size)) {
        node.getChild(i)?.let(queue::addLast)
      }
    }
    return result
  }

  private fun label(node: AccessibilityNodeInfoCompat): String =
    (node.text?.toString()?.takeIf(String::isNotBlank)
      ?: node.contentDescription?.toString().orEmpty()).trim().let(::boundedIdentity)

  // A shared prefix is not an item identity. Retain a digest of long labels instead of silently
  // conflating posts/documents that differ only beyond the memory bound.
  private fun boundedIdentity(value: String): String {
    if (value.length <= 512) return value
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return value.take(440) + "#" + digest
  }

  private fun isDocument(node: AccessibilityNodeInfoCompat): Boolean =
    Role.getRole(node) == Role.ROLE_WEB_VIEW ||
      (WebInterfaceUtils.supportsWebActions(node) &&
        !WebInterfaceUtils.supportsWebActions(node.parent))

  private fun containerId(node: AccessibilityNodeInfoCompat): String =
    "${node.className}|${node.viewIdResourceName.orEmpty()}"

  internal fun describe(node: AccessibilityNodeInfoCompat): RememberedItem {
    val ancestors = ArrayList<String>()
    var container = ""
    var row = node.collectionItemInfo?.rowIndex ?: -1
    var column = node.collectionItemInfo?.columnIndex ?: -1
    var ancestor = node.parent
    var child = node
    var previous = ""
    var next = ""
    var siblingBudget = MAX_SIBLING_VISITS
    repeat(8) {
      val parent = ancestor ?: return@repeat
      ancestors.add(containerId(parent))
      if (container.isEmpty() && row < 0 && parent.collectionItemInfo != null) {
        row = parent.collectionItemInfo.rowIndex
        column = parent.collectionItemInfo.columnIndex
      }
      val nearestContainer = container.isEmpty() &&
        (parent.collectionInfo != null || parent.isScrollable)
      if (nearestContainer) {
        container = containerId(parent)
      }
      // Use siblings of the item wrapper as well as siblings of its focused child.
      if (it == 0 || nearestContainer) {
        for (index in 0 until minOf(parent.childCount, siblingBudget)) {
          siblingBudget--
          if (parent.getChild(index) == child) {
            if (index > 0 && siblingBudget > 0) {
              siblingBudget--
              previous = parent.getChild(index - 1)?.let(::itemText).orEmpty()
            }
            if (index + 1 < parent.childCount && siblingBudget > 0) {
              siblingBudget--
              next = parent.getChild(index + 1)?.let(::itemText).orEmpty()
            }
            break
          }
        }
      }
      child = parent
      ancestor = parent.parent
    }
    val primaryTitle = primaryItemTitle(node)
    return RememberedItem(node.uniqueId.orEmpty(), node.viewIdResourceName.orEmpty(),
      node.className?.toString().orEmpty(), Role.getRole(node), primaryTitle ?: itemText(node),
      if (primaryTitle != null) "" else boundedIdentity(node.contentDescription?.toString().orEmpty()),
      ancestors, previous, next,
      container, row, column)
  }

  private fun primaryItemTitle(node: AccessibilityNodeInfoCompat): String? {
    if (node.isPassword || node.isEditable || node.isScrollable || node.collectionInfo != null ||
      isDocument(node) || WebInterfaceUtils.supportsWebActions(node)) return null
    // Only normalize native rows/cards, never a screen or toolbar containing many items.
    var ancestor = node.parent
    var inRow = node.collectionItemInfo != null
    repeat(8) {
      val parent = ancestor ?: return@repeat
      if (isDocument(parent) || WebInterfaceUtils.supportsWebActions(parent) ||
        parent.packageName != node.packageName) return null
      if (parent.collectionItemInfo != null || parent.collectionInfo != null || parent.isScrollable) {
        inRow = true
        ancestor = null
      } else {
        ancestor = parent.parent
      }
    }
    val isItem = inRow ||
      (Role.getRole(node) == Role.ROLE_VIEW_GROUP &&
        (node.isClickable || node.isScreenReaderFocusable))
    if (!isItem) return null
    val fields = walk(node, 32)
    // An incomplete walk cannot establish that the title is unique.
    if (fields.size >= 32 || fields.drop(1).any {
        it.collectionItemInfo != null || it.collectionInfo != null || it.isScrollable
      }) return null
    val title = fields.filter {
      val id = it.viewIdResourceName?.substringAfterLast('/').orEmpty().lowercase(java.util.Locale.ROOT)
      it.packageName == node.packageName && !it.isPassword && !it.isEditable &&
        !WebInterfaceUtils.supportsWebActions(it) && !it.text.isNullOrBlank() &&
        !id.contains("toolbar") && !id.contains("action_bar") && !id.contains("actionbar") &&
        (it.isHeading || id == "title" || id.endsWith("_title"))
    }.singleOrNull() ?: return null
    // Keep the exposed title verbatim. Read-state descriptions and count/age subtitles may
    // change; stripping words such as "read" would corrupt real titles and localized labels.
    return boundedIdentity(title.text.toString())
  }

  private fun itemText(node: AccessibilityNodeInfoCompat): String {
    if (node.isPassword || node.isEditable) return ""
    primaryItemTitle(node)?.let { return it }
    val own = label(node)
    if (own.isNotEmpty()) return own
    return walk(node, 8).drop(1).filter { !it.isPassword && !it.isEditable }
      .map(::label).filter(String::isNotEmpty).joinToString(" ").let(::boundedIdentity)
  }

  fun shutdown() {
    stopped = true
    prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    handler.removeCallbacksAndMessages(null)
    scrollNode = null
    cachedFocus = null
    observedContext = null
    awaitingWebFocus = false
    awaitingWebScreen = null
    lastWindowId = null
    history.clear()
  }
}
