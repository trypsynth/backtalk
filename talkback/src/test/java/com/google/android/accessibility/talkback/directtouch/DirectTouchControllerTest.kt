/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.directtouch

import android.accessibilityservice.AccessibilityService
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_ADDED
import android.view.accessibility.AccessibilityEvent.WINDOWS_CHANGE_REMOVED
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.utils.input.WindowEventInterpreter
import com.google.android.accessibility.utils.monitor.DisplayMonitor
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityWindowInfo
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Feeds window changes through the window interpreter to direct touch, as the service does, and
 * records what direct touch says and which passthrough regions it sends. It also counts what each
 * look at the screen costs: fetching a window's root waits for the window's app, as does asking it
 * for its marked views, and reading an app's manifest asks the package manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [34],
  qualifiers = "w360dp-h780dp-xxhdpi",
  shadows =
    [
      DirectTouchControllerTest.WindowsShadow::class,
      DirectTouchControllerTest.RootsShadow::class,
      DirectTouchControllerTest.ManifestsShadow::class,
      DirectTouchControllerTest.ViewIdsShadow::class,
    ],
)
class DirectTouchControllerTest {

  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
  }

  /** Lists the windows a test puts on screen, in a new list each time as Android does. */
  @Implements(AccessibilityService::class)
  class WindowsShadow : ShadowAccessibilityService() {
    var onScreen: List<AccessibilityWindowInfo> = emptyList()

    @Implementation
    override fun getWindows(): List<AccessibilityWindowInfo> = ArrayList(onScreen)

    @Implementation
    override fun getWindowsOnAllDisplays(): SparseArray<List<AccessibilityWindowInfo>> =
      SparseArray<List<AccessibilityWindowInfo>>().apply {
        put(Display.DEFAULT_DISPLAY, ArrayList(onScreen))
      }
  }

  /** Counts how often a window's root is fetched. */
  @Implements(AccessibilityWindowInfo::class)
  class RootsShadow : ShadowAccessibilityWindowInfo() {
    @Implementation
    override fun getRoot(): AccessibilityNodeInfo? {
      rootRequests++
      return super.getRoot()
    }

    companion object {
      var rootRequests = 0
    }
  }

  /** Counts how often the package manager is asked for an app's manifest, with its meta-data. */
  @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
  class ManifestsShadow : ShadowApplicationPackageManager() {
    @Implementation
    override fun getApplicationInfo(packageName: String, flags: Int): ApplicationInfo {
      if (flags and PackageManager.GET_META_DATA != 0) {
        manifestRequests++
      }
      return super.getApplicationInfo(packageName, flags)
    }

    companion object {
      var manifestRequests = 0
    }
  }

  /**
   * Answers a window's root when asked for its views by ID, with the views a test marked in the
   * root's app, and counts the asks. On a device the app searches its own views to answer.
   */
  @Implements(AccessibilityNodeInfo::class)
  class ViewIdsShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    @Implementation
    fun findAccessibilityNodeInfosByViewId(viewId: String): List<AccessibilityNodeInfo> {
      lookups++
      return marked[node.packageName?.toString() to viewId].orEmpty()
    }

    companion object {
      var lookups = 0
      val marked = HashMap<Pair<String?, String>, List<AccessibilityNodeInfo>>()
    }
  }

  private lateinit var service: TestService
  private lateinit var serviceWindows: WindowsShadow
  private lateinit var interpreter: WindowEventInterpreter
  private lateinit var controller: DirectTouchController
  private val prefs = FakeSharedPreferences()
  private val transcript = mutableListOf<String>()

  @Before
  fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    serviceWindows = Shadow.extract(service)
    interpreter = WindowEventInterpreter(service, DisplayMonitor(service))
    controller =
      DirectTouchController(
        service,
        prefs,
        Pipeline.FeedbackReturner { feedback ->
          transcript += describe(feedback)
          true
        },
        interpreter,
      )
    interpreter.addListener(controller)
    installApp(GAME_PACKAGE, asksForDirectTouch = false)
    installApp(GAME2_PACKAGE, asksForDirectTouch = true)
    installApp(PLAYER_PACKAGE, asksForDirectTouch = false, asksForRegions = true)
    RootsShadow.rootRequests = 0
    ManifestsShadow.manifestRequests = 0
    ViewIdsShadow.lookups = 0
    ViewIdsShadow.marked.clear()
  }

  @Test
  fun gameNotTurnedOnStaysOff() {
    windowsChange(GAME, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game())
    // A toast comes and goes over the game.
    windowsChange(TOAST, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game(), toast())
    windowsChange(TOAST, WINDOWS_CHANGE_REMOVED, statusBar(), navigationBar(), game())

    assertTranscript()
    // Direct touch looks at the screen several times for each window change. The game's root is
    // fetched once, and its manifest is read once, the first time the game is seen.
    assertEquals(1, RootsShadow.rootRequests)
    assertEquals(1, ManifestsShadow.manifestRequests)
  }

  @Test
  fun gameTurnedOnHandsTouchBackWhileTheShadeIsOpen() {
    DirectTouchSettings.setAppEnabled(prefs, GAME_PACKAGE, true)

    windowsChange(GAME, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game())
    val underShade = game(inactive = true)
    windowsChange(SHADE, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), underShade, shade())
    windowsChange(SHADE, WINDOWS_CHANGE_REMOVED, statusBar(), navigationBar(), game())

    assertTranscript(
      "region [0,80][1080,2220] complex=false",
      "say \"Direct touch on\"",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
      "region none",
      "say \"Direct touch off\"",
      "region [0,80][1080,2220] complex=false",
      "say \"Direct touch on\"",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
    )
    // Only the game's root and the shade's are fetched, once each. The bars are too small to be the
    // shade, so they are never asked whether they belong to System UI.
    assertEquals(2, RootsShadow.rootRequests)
    assertEquals(1, ManifestsShadow.manifestRequests)
  }

  @Test
  fun gameThatAsksForDirectTouchGetsItTheFirstTimeItIsSeen() {
    windowsChange(GAME2, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game2())
    windowsChange(TOAST, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game2(), toast())
    windowsChange(TOAST, WINDOWS_CHANGE_REMOVED, statusBar(), navigationBar(), game2())

    assertTranscript(
      "region [0,80][1080,2220] complex=false",
      "say \"Direct touch on\"",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
      "region [0,80][1080,2220] complex=false",
    )
    assertEquals(1, RootsShadow.rootRequests)
    assertEquals(1, ManifestsShadow.manifestRequests)
  }

  @Test
  fun appThatMarksAViewGetsDirectTouchOnlyThere() {
    mark(PAD)

    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())

    assertTranscript(
      "region [0,1400][1080,2200] complex=false",
      "say \"Direct touch on\"",
      "region [0,1400][1080,2200] complex=false",
      "region [0,1400][1080,2200] complex=false",
    )
    // Its manifest is read once, the first time it is seen, and its root is fetched once for its
    // package and once to ask for marked views. Each look is one ask, however many views it has.
    assertEquals(1, ManifestsShadow.manifestRequests)
    assertEquals(2, RootsShadow.rootRequests)
    assertEquals(3, ViewIdsShadow.lookups)
  }

  @Test
  fun theMarkedViewComingAndGoingIsNotAnnounced() {
    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())
    mark(PAD)
    contentChange(PLAYER_PACKAGE, PLAYER)
    mark()
    contentChange(PLAYER_PACKAGE, PLAYER)
    // Another app opening on top is the end of the player's direct touch.
    windowsChange(GAME, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player(inactive = true), game())

    assertTranscript(
      // Direct touch is on for the player as soon as it is in front, with nothing marked yet.
      "say \"Direct touch on\"",
      "region [0,1400][1080,2200] complex=false",
      "region none",
      "say \"Direct touch off\"",
    )
  }

  @Test
  fun aStreamOfContentChangesIsLookedAtAtMostEvery150Milliseconds() {
    mark(PAD)
    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())
    transcript.clear()
    ViewIdsShadow.lookups = 0

    // A player reports its position over and over: a change every 10 ms for a second.
    repeat(100) {
      contentChange(PLAYER_PACKAGE, PLAYER, settle = Duration.ofMillis(10))
    }
    settle()

    // One look straight away, then one every 150 ms, the last for the changes after 900 ms.
    assertEquals(8, ViewIdsShadow.lookups)
    // The window's root is not fetched again for any of them.
    assertEquals(2, RootsShadow.rootRequests)
    assertTranscript(*Array(8) { "region [0,1400][1080,2200] complex=false" })
  }

  @Test
  fun theFirstChangeInAWhileIsLookedAtAtOnce() {
    mark(PAD)
    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())
    transcript.clear()

    // The marked view goes away, and touch has to come back to Backtalk without waiting.
    mark()
    contentChange(PLAYER_PACKAGE, PLAYER, settle = Duration.ZERO)

    assertTranscript("region none")
  }

  @Test
  fun otherAppsAreNeverAskedForMarkedViews() {
    DirectTouchSettings.setAppEnabled(prefs, GAME_PACKAGE, true)
    windowsChange(GAME, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game())
    repeat(20) { contentChange(GAME_PACKAGE, GAME) }
    windowsChange(GAME2, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), game2())
    repeat(20) { contentChange(GAME2_PACKAGE, GAME2) }

    assertEquals(0, ViewIdsShadow.lookups)
  }

  @Test
  fun aRegionAppTurnedOffIsNotAsked() {
    DirectTouchSettings.onFirstSight(prefs, PLAYER_PACKAGE, true)
    DirectTouchSettings.setAppEnabled(prefs, PLAYER_PACKAGE, false)
    mark(PAD)

    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())
    repeat(20) { contentChange(PLAYER_PACKAGE, PLAYER) }

    assertTranscript()
    assertEquals(0, ViewIdsShadow.lookups)
    assertEquals(0, ManifestsShadow.manifestRequests)
  }

  @Test
  fun theAppsOwnDialogIsCutOutOfTheRegion() {
    mark(PAD)
    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())
    transcript.clear()

    windowsChange(
      PLAYER_DIALOG,
      WINDOWS_CHANGE_ADDED,
      statusBar(),
      navigationBar(),
      player(),
      playerDialog(),
    )

    // The dialog covers the top of the pad, and direct touch stays on for the rest of it.
    assertTranscript(
      "region [0,1800][1080,2200] complex=false",
      "region [0,1800][1080,2200] complex=false",
    )
  }

  @Test
  fun theScreenTurningOffEndsDirectTouchOnce() {
    windowsChange(PLAYER, WINDOWS_CHANGE_ADDED, statusBar(), navigationBar(), player())

    controller.onScreenChanged(false, null)
    controller.onScreenChanged(true, null)
    settle()

    // Nothing is marked, so there is no region to clear, but direct touch still ends and starts.
    assertTranscript(
      "say \"Direct touch on\"",
      "say \"Direct touch off\"",
      "say \"Direct touch on\"",
    )
  }

  // Screens

  private fun statusBar() =
    window(STATUS_BAR, SYSTEM, "Status bar", Rect(0, 0, 1080, 80), SYSTEM_UI)

  private fun navigationBar() =
    window(NAVIGATION_BAR, SYSTEM, "Navigation bar", Rect(0, 2220, 1080, 2340), SYSTEM_UI)

  private fun game(inactive: Boolean = false) =
    window(GAME, APPLICATION, "Game", SCREEN, GAME_PACKAGE, active = !inactive)

  private fun game2() = window(GAME2, APPLICATION, "Game 2", SCREEN, GAME2_PACKAGE, active = true)

  private fun toast() = window(TOAST, SYSTEM, "Saved", Rect(200, 1900, 880, 2050), SYSTEM_UI)

  private fun player(inactive: Boolean = false) =
    window(PLAYER, APPLICATION, "Player", SCREEN, PLAYER_PACKAGE, active = !inactive)

  private fun playerDialog() =
    window(PLAYER_DIALOG, APPLICATION, "Speed", Rect(0, 900, 1080, 1800), PLAYER_PACKAGE, layer = 2)

  private fun shade() =
    window(SHADE, SYSTEM, "Notification shade", SCREEN, SYSTEM_UI, active = true, layer = 9)

  /** A window as Android lists it, with a title, so that the interpreter needn't name it. */
  private fun window(
    id: Int,
    type: Int,
    title: String,
    bounds: Rect,
    rootPackage: String,
    active: Boolean = false,
    layer: Int = if (type == SYSTEM) 5 else 1,
  ): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    val shadow = shadowOf(window)
    shadow.setId(id)
    shadow.setType(type)
    shadow.setTitle(title)
    shadow.setBoundsInScreen(bounds)
    shadow.setLayer(layer)
    shadow.setActive(active)
    shadow.setFocused(active)
    shadow.setDisplayId(Display.DEFAULT_DISPLAY)
    shadow.setRoot(AccessibilityNodeInfo().apply { packageName = rootPackage })
    return window
  }

  private fun installApp(
    packageName: String,
    asksForDirectTouch: Boolean,
    asksForRegions: Boolean = false,
  ) {
    val info = PackageInfo()
    info.packageName = packageName
    info.applicationInfo =
      ApplicationInfo().apply {
        this.packageName = packageName
        metaData =
          Bundle().apply {
            putBoolean(DirectTouchSettings.CAPABILITY_KEY, asksForDirectTouch)
            putBoolean(DirectTouchSettings.REGIONS_CAPABILITY_KEY, asksForRegions)
          }
      }
    shadowOf(service.packageManager).installPackage(info)
  }

  /** The player shows views with the marking ID at [areas] in its main window, and only those. */
  private fun mark(vararg areas: Rect) {
    ViewIdsShadow.marked[PLAYER_PACKAGE to MARKED_VIEW_ID] =
      areas.map { area ->
        AccessibilityNodeInfo().apply {
          packageName = PLAYER_PACKAGE
          isVisibleToUser = true
          setBoundsInScreen(area)
          ReflectionHelpers.setField(this, "mWindowId", PLAYER)
        }
      }
  }

  // Events

  /** The windows change to [windows], Android reports it, and things settle for a second. */
  private fun windowsChange(windowId: Int, changes: Int, vararg windows: AccessibilityWindowInfo) {
    serviceWindows.onScreen = windows.toList()
    val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    ReflectionHelpers.callInstanceMethod<Any>(
      event,
      "setWindowId",
      ClassParameter.from(Int::class.javaPrimitiveType, windowId),
    )
    ReflectionHelpers.callInstanceMethod<Any>(
      event,
      "setWindowChanges",
      ClassParameter.from(Int::class.javaPrimitiveType, changes),
    )
    event.eventTime = SystemClock.uptimeMillis()
    interpreter.interpret(event, null)
    controller.onAccessibilityEvent(event)
    settle()
  }

  /** An app reports that its content changed in a window, and [settle] passes. */
  private fun contentChange(
    packageName: String,
    windowId: Int,
    settle: Duration = Duration.ofSeconds(1),
  ) {
    val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    event.packageName = packageName
    ReflectionHelpers.callInstanceMethod<Any>(
      event,
      "setWindowId",
      ClassParameter.from(Int::class.javaPrimitiveType, windowId),
    )
    event.eventTime = SystemClock.uptimeMillis()
    interpreter.interpret(event, null)
    controller.onAccessibilityEvent(event)
    shadowOf(Looper.getMainLooper()).idleFor(settle)
  }

  private fun settle() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
  }

  // Transcript

  private fun assertTranscript(vararg expected: String) {
    assertEquals(expected.joinToString("\n"), transcript.joinToString("\n"))
  }

  private fun describe(feedback: Feedback): String {
    val part = feedback.failovers().first()
    part.speech()?.let {
      return "say \"${it.text()}\""
    }
    part.passThroughMode()?.let {
      val region = it.region()
      return if (region.isEmpty) {
        "region none"
      } else {
        "region ${region.bounds.toShortString()} complex=${region.isComplex}"
      }
    }
    return part.toString()
  }

  private companion object {
    const val STATUS_BAR = 1
    const val NAVIGATION_BAR = 2
    const val SHADE = 3
    const val TOAST = 4
    const val GAME = 10
    const val GAME2 = 11
    const val PLAYER = 12
    const val PLAYER_DIALOG = 13
    const val GAME_PACKAGE = "com.example.game"
    const val GAME2_PACKAGE = "com.example.game2"
    const val PLAYER_PACKAGE = "com.example.player"
    const val MARKED_VIEW_ID = "$PLAYER_PACKAGE:id/${DirectTouchSettings.REGION_VIEW_ID}"
    const val SYSTEM_UI = "com.android.systemui"
    const val SYSTEM = AccessibilityWindowInfo.TYPE_SYSTEM
    const val APPLICATION = AccessibilityWindowInfo.TYPE_APPLICATION
    val SCREEN = Rect(0, 0, 1080, 2340)
    // The player's touch pad, below its buttons.
    val PAD = Rect(0, 1400, 1080, 2200)
  }
}
