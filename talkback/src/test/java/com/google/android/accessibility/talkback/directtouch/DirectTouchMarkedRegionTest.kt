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

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.graphics.Region
import android.os.Bundle
import com.google.android.accessibility.talkback.directtouch.DirectTouchRegions.LayeredWindow
import com.google.android.accessibility.talkback.directtouch.DirectTouchRegions.MarkedArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Region arithmetic needs Android's own Region, so these run under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectTouchMarkedRegionTest {
  private val app = LayeredWindow(id = 1, layer = 1, bounds = Rect(0, 0, 1080, 2400), alwaysExcluded = false)
  private val statusBar =
    LayeredWindow(id = 2, layer = 5, bounds = Rect(0, 0, 1080, 100), alwaysExcluded = true)
  private val gestureArea = MarkedArea(windowId = 1, bounds = Rect(0, 1200, 1080, 2400))

  @Test
  fun theMarkedViewTakesTouchesAndTheRestDoesNot() {
    val region = DirectTouchRegions.markedRegion(listOf(gestureArea), listOf(app))
    assertEquals(Rect(0, 1200, 1080, 2400), region.bounds)
    assertTrue(region.contains(540, 1800))
    assertFalse(region.contains(540, 600))
  }

  @Test
  fun noMarkedViewsMeansNothingPassesThrough() {
    assertTrue(DirectTouchRegions.markedRegion(emptyList(), listOf(app)).isEmpty)
  }

  @Test
  fun aViewIsClippedToItsWindow() {
    val popup = LayeredWindow(id = 3, layer = 2, bounds = Rect(100, 100, 500, 500), alwaysExcluded = false)
    val overflowing = MarkedArea(windowId = 3, bounds = Rect(0, 0, 1080, 1000))
    val region = DirectTouchRegions.markedRegion(listOf(overflowing), listOf(app, popup))
    assertEquals(Rect(100, 100, 500, 500), region.bounds)
  }

  @Test
  fun aDialogOnTopIsCutOut() {
    val dialog = LayeredWindow(id = 3, layer = 2, bounds = Rect(100, 1500, 980, 2000), alwaysExcluded = false)
    val region = DirectTouchRegions.markedRegion(listOf(gestureArea), listOf(app, dialog))
    assertFalse(region.contains(540, 1700))
    assertTrue(region.contains(540, 1300))
    assertTrue(region.contains(540, 2200))
  }

  @Test
  fun aWindowUnderneathIsNotCutOut() {
    val below = LayeredWindow(id = 3, layer = 0, bounds = Rect(0, 1200, 1080, 2400), alwaysExcluded = false)
    val region = DirectTouchRegions.markedRegion(listOf(gestureArea), listOf(app, below))
    assertTrue(region.contains(540, 1800))
  }

  @Test
  fun systemWindowsAndTheKeyboardAreAlwaysCutOut() {
    val navBar = LayeredWindow(id = 4, layer = 0, bounds = Rect(0, 2300, 1080, 2400), alwaysExcluded = true)
    val fullScreen = MarkedArea(windowId = 1, bounds = Rect(0, 0, 1080, 2400))
    val region = DirectTouchRegions.markedRegion(listOf(fullScreen), listOf(app, statusBar, navBar))
    assertFalse(region.contains(540, 50))
    assertFalse(region.contains(540, 2350))
    assertTrue(region.contains(540, 1200))
  }

  @Test
  fun aViewInAWindowThatIsGoneIsLeftOut() {
    val stale = MarkedArea(windowId = 9, bounds = Rect(0, 0, 1080, 2400))
    assertTrue(DirectTouchRegions.markedRegion(listOf(stale), listOf(app)).isEmpty)
  }

  @Test
  fun severalMarkedViewsAddUp() {
    val top = MarkedArea(windowId = 1, bounds = Rect(0, 200, 1080, 400))
    val region = DirectTouchRegions.markedRegion(listOf(top, gestureArea), listOf(app))
    assertTrue(region.contains(540, 300))
    assertTrue(region.contains(540, 1800))
    assertFalse(region.contains(540, 800))
    val expected = Region(0, 200, 1080, 400).apply { union(Rect(0, 1200, 1080, 2400)) }
    assertEquals(expected, region)
  }

  @Test
  fun capabilityComesFromEitherKey() {
    assertEquals(DirectTouchCapability.NONE, DirectTouchCapability.fromMetaData(null))
    val whole = Bundle().apply { putBoolean(DirectTouchSettings.CAPABILITY_KEY, true) }
    val regions = Bundle().apply { putBoolean(DirectTouchSettings.REGIONS_CAPABILITY_KEY, true) }
    assertEquals(
      DirectTouchCapability(wholeScreen = true, regions = false),
      DirectTouchCapability.fromMetaData(whole),
    )
    assertEquals(
      DirectTouchCapability(wholeScreen = false, regions = true),
      DirectTouchCapability.fromMetaData(regions),
    )
    assertTrue(DirectTouchCapability.fromMetaData(regions).any)
    assertFalse(DirectTouchCapability.NONE.any)
  }

  @Test
  fun theActivityAddsToWhatTheApplicationAsks() {
    val both =
      DirectTouchCapability(wholeScreen = true, regions = false) +
        DirectTouchCapability(wholeScreen = false, regions = true)
    assertEquals(DirectTouchCapability(wholeScreen = true, regions = true), both)
  }

  @Test
  fun capabilityIsReadFromTheInstalledApp() {
    val pm = RuntimeEnvironment.getApplication().packageManager
    val metaData = Bundle().apply { putBoolean(DirectTouchSettings.REGIONS_CAPABILITY_KEY, true) }
    shadowOf(pm)
      .installPackage(
        PackageInfo().apply {
          packageName = "dev.example.player"
          applicationInfo =
            ApplicationInfo().apply {
              packageName = "dev.example.player"
              this.metaData = metaData
            }
        }
      )
    assertEquals(
      DirectTouchCapability(wholeScreen = false, regions = true),
      DirectTouchCapability.of(pm, "dev.example.player"),
    )
    assertEquals(DirectTouchCapability.NONE, DirectTouchCapability.of(pm, "dev.example.missing"))
  }
}
