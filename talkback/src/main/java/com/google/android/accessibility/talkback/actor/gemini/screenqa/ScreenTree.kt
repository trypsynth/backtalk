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
 * License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.gemini.screenqa

import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.gemineye.api.AccessibilityTree
import com.google.android.accessibility.gemineye.api.NodeId
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.protobuf.ByteString

/**
 * Numbers the visible nodes of the window that holds a node, so that Gemini can refer to screen
 * elements by number and TalkBack can find the live nodes again.
 */
class ScreenTree private constructor(private val nodes: List<AccessibilityNodeInfoCompat>) :
  AccessibilityTree {

  /** One line per node that has content or can be acted on, for the Gemini prompt. */
  val description: String by lazy {
    nodes
      .withIndex()
      .mapNotNull { (id, node) -> describe(id, node) }
      .joinToString(separator = "\n")
  }

  // Node numbers are unique across the whole tree, so the window ID is not needed to find a node.
  override fun findNodeById(nodeId: NodeId): AccessibilityNodeInfoCompat? =
    nodes.getOrNull(nodeId.uniqueId)

  override fun serialize(): ByteString = ByteString.copyFromUtf8(description)

  private fun describe(id: Int, node: AccessibilityNodeInfoCompat): String? {
    val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.take(MAX_LABEL_LENGTH)
    val traits =
      listOfNotNull(
        "clickable".takeIf { node.isClickable },
        "editable".takeIf { node.isEditable },
        "checked".takeIf { node.isCheckable && node.isChecked },
        "not checked".takeIf { node.isCheckable && !node.isChecked },
        "scrollable".takeIf { node.isScrollable },
        "heading".takeIf { node.isHeading },
      )
    if (label.isNullOrEmpty() && traits.isEmpty()) {
      return null
    }
    val bounds = Rect().also { node.getBoundsInScreen(it) }
    val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
    return buildString {
      append("$id: $className")
      if (!label.isNullOrEmpty()) append(" \"$label\"")
      if (traits.isNotEmpty()) append(" (${traits.joinToString()})")
      append(" [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
    }
  }

  companion object {
    private const val MAX_NODES = 400
    private const val MAX_LABEL_LENGTH = 120

    /** Collects the visible nodes of the window that holds [node], in breadth-first order. */
    @JvmStatic
    fun fromNode(node: AccessibilityNodeInfoCompat): ScreenTree {
      val root = AccessibilityNodeInfoUtils.getRoot(node) ?: node
      val nodes = mutableListOf<AccessibilityNodeInfoCompat>()
      val queue = ArrayDeque(listOf(root))
      while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
        val current = queue.removeFirst()
        if (!AccessibilityNodeInfoUtils.isVisible(current)) {
          continue
        }
        nodes.add(current)
        for (index in 0 until current.childCount) {
          current.getChild(index)?.let { queue.addLast(it) }
        }
      }
      return ScreenTree(nodes)
    }
  }
}
