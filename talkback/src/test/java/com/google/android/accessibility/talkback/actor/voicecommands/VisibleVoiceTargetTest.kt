/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisibleVoiceTargetTest {
  class TestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  private val root = node(bounds = Rect(0, 0, 300, 600))
  private val windows = listOf(VisibleVoiceTarget.Window(1, root, Region(0, 0, 300, 600)))

  private fun node(
    text: String = "",
    parent: AccessibilityNodeInfoCompat? = null,
    clickable: Boolean = false,
    bounds: Rect = Rect(10, 10, 110, 60),
  ): AccessibilityNodeInfoCompat {
    val raw =
      AccessibilityNodeInfo.obtain().apply {
        this.text = text
        packageName = "example.app"
        isVisibleToUser = true
        isEnabled = true
        isClickable = clickable
        setBoundsInScreen(bounds)
      }
    parent?.let { shadowOf(it.unwrap()).addChild(raw) }
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  @Test
  fun directButtonAndIconDescriptionMatch() {
    val save = node("Save", root, true)
    val close = node(parent = root, clickable = true).apply { contentDescription = "Close" }
    assertEquals(save, VisibleVoiceTarget.find("save", windows).target?.control)
    assertEquals(close, VisibleVoiceTarget.find("close", windows).target?.control)
  }

  @Test
  fun aVisibleLabelClicksItsNearestClickableParent() {
    val button = node(parent = root, clickable = true)
    val label = node("Save changes", button)
    val result = VisibleVoiceTarget.find("save changes", windows)
    assertEquals(label, result.target?.label)
    assertEquals(button, result.target?.control)
    assertNull(VisibleVoiceTarget.find("save", windows).target)
  }

  @Test
  fun staticTextAndEditableValuesAreNotButtons() {
    node("Save", root)
    val field = node("Send", root, true).apply { isEditable = true }
    node("Send", field)
    assertNull(VisibleVoiceTarget.find("Save", windows).target)
    assertNull(VisibleVoiceTarget.find("Send", windows).target)
  }

  @Test
  fun invisibleDisabledEmptyAndOffScreenItemsAreExcluded() {
    node("Hidden", root, true).isVisibleToUser = false
    node("Disabled", root, true).isEnabled = false
    node("Empty", root, true, Rect(10, 10, 10, 10))
    node("Outside", root, true, Rect(10, 700, 100, 750))
    for (label in listOf("Hidden", "Disabled", "Empty", "Outside", "Missing")) {
      assertNull(label, VisibleVoiceTarget.find(label, windows).target)
    }
  }

  @Test
  fun itemsBehindAnotherWindowOrOutsideTheirContainerAreExcluded() {
    node("Covered", root, true)
    val uncovered = listOf(VisibleVoiceTarget.Window(1, root, Region(0, 200, 300, 600)))
    assertNull(VisibleVoiceTarget.find("Covered", uncovered).target)
    val container = node(parent = root, bounds = Rect(0, 0, 300, 100))
    node("Clipped", container, true, Rect(0, 120, 100, 180))
    assertNull(VisibleVoiceTarget.find("Clipped", windows).target)
  }

  @Test
  fun hiddenAndDisabledParentsExcludeTheirChildren() {
    val hidden = node(parent = root).apply { isVisibleToUser = false }
    node("Hidden child", hidden, true)
    val disabled = node(parent = root).apply { isEnabled = false }
    node("Disabled child", disabled, true)
    assertNull(VisibleVoiceTarget.find("Hidden child", windows).target)
    assertNull(VisibleVoiceTarget.find("Disabled child", windows).target)
  }

  @Test
  fun duplicateButtonsAreRejectedButRepeatedLabelsInsideOneButtonAreFine() {
    val button = node("Save", root, true)
    node("Save", button)
    assertEquals(button, VisibleVoiceTarget.find("Save", windows).target?.control)
    node("Save", root, true)
    assertTrue(VisibleVoiceTarget.find("Save", windows).failed)
    assertNull(VisibleVoiceTarget.find("Save", windows).target)
  }

  @Test
  fun aPartiallyVisibleButtonCanBeClicked() {
    val button = node("Save", root, true, Rect(250, 550, 350, 650))
    assertEquals(button, VisibleVoiceTarget.find("Save", windows).target?.control)
  }

  @Test
  fun oversizedTreesAreRefusedWithoutAnUnboundedSearch() {
    repeat(2001) { node("Item $it", root, true) }
    assertTrue(VisibleVoiceTarget.find("Item 0", windows).failed)
  }

  @Test
  @Config(sdk = [26, 34])
  fun onlyOtherAppsAreSearchedAndExcludedWindowsStillCoverUnderlyingControls() {
    val service = Robolectric.buildService(TestService::class.java).get()
    node("Covered", root, true)
    val visible = node("Visible", root, true, Rect(0, 220, 100, 270))
    val background =
      AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setId(1)
        shadowOf(it).setLayer(0)
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setBoundsInScreen(Rect(0, 0, 300, 600))
        shadowOf(it).setRoot(root.unwrap())
      }
    val excluded =
      listOf(
        AccessibilityWindowInfo.TYPE_INPUT_METHOD to "example.keyboard",
        AccessibilityWindowInfo.TYPE_SYSTEM to "example.system",
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY to "example.overlay",
        AccessibilityWindowInfo.TYPE_APPLICATION to service.packageName,
        AccessibilityWindowInfo.TYPE_APPLICATION to "com.android.systemui",
        AccessibilityWindowInfo.TYPE_APPLICATION to "android",
      )
    for ((type, owner) in excluded) {
      val foregroundRoot = node("Send", clickable = true).apply { packageName = owner }
      val foreground =
        AccessibilityWindowInfo.obtain().also {
          shadowOf(it).setId(2)
          shadowOf(it).setLayer(1)
          shadowOf(it).setType(type)
          shadowOf(it).setBoundsInScreen(Rect(0, 0, 300, 200))
          shadowOf(it).setRoot(foregroundRoot.unwrap())
        }
      shadowOf(service).setWindows(listOf(background, foreground))
      val captured = VisibleVoiceTarget.windows(service)
      assertNull(owner, VisibleVoiceTarget.find("Send", captured).target)
      assertNull(owner, VisibleVoiceTarget.find("Covered", captured).target)
      assertEquals(visible, VisibleVoiceTarget.find("Visible", captured).target?.control)
      assertFalse(captured.first { it.id == 2 }.searchable)
      assertFalse(VisibleVoiceTarget.windows(service, false).first { it.id == 2 }.searchable)
    }
  }

  @Test
  @Config(sdk = [26, 34])
  fun windowCaptureExcludesAreasCoveredByAHigherWindow() {
    val service = Robolectric.buildService(TestService::class.java).get()
    val background =
      AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setId(1)
        shadowOf(it).setLayer(0)
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setBoundsInScreen(Rect(0, 0, 300, 600))
        shadowOf(it).setRoot(root.unwrap())
      }
    val dialog =
      AccessibilityWindowInfo.obtain().also {
        shadowOf(it).setId(2)
        shadowOf(it).setLayer(1)
        shadowOf(it).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(it).setBoundsInScreen(Rect(0, 0, 300, 200))
      }
    shadowOf(service).setWindows(listOf(background, dialog))
    node("Covered", root, true)
    val visible = node("Visible", root, true, Rect(0, 220, 100, 270))
    val captured = VisibleVoiceTarget.windows(service)
    assertNull(VisibleVoiceTarget.find("Covered", captured).target)
    assertEquals(visible, VisibleVoiceTarget.find("Visible", captured).target?.control)
    assertTrue(VisibleVoiceTarget.windows(service, includeRoots = false).all { it.root == null })
    // A non-focusable accessibility overlay can also cover a button; it must not be clicked
    // through.
    shadowOf(dialog).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    shadowOf(service).setWindows(listOf(background, dialog))
    assertNull(VisibleVoiceTarget.find("Covered", VisibleVoiceTarget.windows(service)).target)
  }
}
