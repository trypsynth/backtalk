/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.ArrayDeque

/** Finds only a uniquely named, enabled item that can be clicked without scrolling. */
internal object VisibleVoiceTarget {
  data class Window(
    val id: Int,
    val root: AccessibilityNodeInfoCompat?,
    val visible: Region,
    val searchable: Boolean = true,
    val packageName: String? = root?.packageName?.toString(),
  )

  data class Target(
    val label: AccessibilityNodeInfoCompat,
    val control: AccessibilityNodeInfoCompat,
    val windowId: Int,
    val packageName: String?,
  )

  data class Search(val target: Target? = null, val failed: Boolean = false)

  private data class Entry(
    val node: AccessibilityNodeInfoCompat,
    val control: AccessibilityNodeInfoCompat?,
    val clip: Region,
  )

  private const val MAX_NODES = 2000
  private const val MAX_SEARCH_MS = 1500L

  @Suppress("DEPRECATION") // getRealSize also works on the minimum supported Android version.
  fun windows(service: AccessibilityService, includeRoots: Boolean = true): List<Window> {
    val size = Point()
    service.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
    val screen = Region(0, 0, size.x, size.y)
    val covered = Region()
    return service.windows
      .sortedByDescending { it.layer }
      .mapNotNull { window ->
        val region =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Region().also { window.getRegionInScreen(it) }
          } else {
            Region(Rect().also { window.getBoundsInScreen(it) })
          }
        region.op(screen, Region.Op.INTERSECT)
        val visible = Region(region).apply { op(covered, Region.Op.DIFFERENCE) }
        covered.op(region, Region.Op.UNION)
        if (visible.isEmpty) null
        else {
          val root =
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
              window.root?.let(AccessibilityNodeInfoCompat::wrap)
            } else null
          val owner = root?.packageName?.toString()
          val searchable =
            !owner.isNullOrEmpty() &&
              owner != service.packageName &&
              owner != "com.android.systemui" &&
              owner != "android"
          Window(
            window.id,
            if (includeRoots && searchable) root else null,
            visible,
            searchable,
            owner,
          )
        }
      }
  }

  fun find(command: String, windows: List<Window>): Search {
    if (VoiceControlMatching.normalize(command).isEmpty()) return Search()
    val deadline = SystemClock.uptimeMillis() + MAX_SEARCH_MS
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    var match: Target? = null
    for (window in windows) {
      if (!window.searchable) continue
      val queue = ArrayDeque<Entry>()
      window.root?.let { queue.add(Entry(it, null, window.visible)) }
      while (queue.isNotEmpty()) {
        if (
          visited.size >= MAX_NODES ||
            SystemClock.uptimeMillis() > deadline ||
            Thread.currentThread().isInterrupted
        )
          return Search(failed = true)
        val (node, parentControl, clip) = queue.removeFirst()
        if (!visited.add(node)) continue
        val shown = visiblePart(node, clip) ?: continue
        if (!node.isEnabled) continue
        val control = if (canClick(node)) node else parentControl
        if (control != null && hasLabel(node, command)) {
          if (match != null && match.control != control) return Search(failed = true)
          match = Target(node, control, window.id, window.packageName)
        }
        // Text inside an editable field is its value, not the name of an enclosing button.
        if (node.isEditable) continue
        if (node.childCount > MAX_NODES - visited.size - queue.size) return Search(failed = true)
        for (i in 0 until node.childCount) {
          if (SystemClock.uptimeMillis() > deadline || Thread.currentThread().isInterrupted) {
            return Search(failed = true)
          }
          node.getChild(i)?.let { queue.add(Entry(it, control, shown)) }
        }
      }
    }
    return Search(match)
  }

  /** Refreshes the matched path just before acting; a saved focus is never used as a fallback. */
  fun stillVisible(target: Target, command: String, windows: List<Window>): Boolean {
    val window =
      windows.firstOrNull {
        it.id == target.windowId && it.searchable && it.packageName == target.packageName
      } ?: return false
    if (
      !target.control.refresh() ||
        !target.label.refresh() ||
        target.control.windowId != target.windowId ||
        target.label.windowId != target.windowId ||
        !canClick(target.control) ||
        !hasLabel(target.label, command)
    )
      return false
    var node: AccessibilityNodeInfoCompat? = target.label
    var includesControl = false
    var shown = Region(window.visible)
    val visited = HashSet<AccessibilityNodeInfoCompat>()
    while (node != null) {
      if (visited.size >= 100 || !visited.add(node) || !node.refresh() || !node.isEnabled) {
        return false
      }
      shown = visiblePart(node, shown) ?: return false
      if (node == target.control) includesControl = true
      node = node.parent
    }
    return includesControl
  }

  private fun hasLabel(node: AccessibilityNodeInfoCompat, command: String): Boolean =
    !node.isEditable &&
      (VoiceControlMatching.sameLabel(command, node.text) ||
        VoiceControlMatching.sameLabel(command, node.contentDescription))

  private fun canClick(node: AccessibilityNodeInfoCompat): Boolean =
    !node.isEditable &&
      (node.isClickable ||
        node.actionList.any {
          it.id == AccessibilityNodeInfoCompat.ACTION_CLICK
        })

  private fun visiblePart(node: AccessibilityNodeInfoCompat, clip: Region): Region? {
    if (!node.isVisibleToUser) return null
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    if (bounds.isEmpty) return null
    return Region(clip).takeIf { it.op(bounds, Region.Op.INTERSECT) }
  }
}
