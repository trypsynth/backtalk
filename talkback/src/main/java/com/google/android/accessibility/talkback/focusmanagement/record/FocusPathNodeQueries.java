/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record;

import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.utils.AccessibilityNode;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Reads the nodes needed for a focus snapshot without prefetching unrelated subtrees. */
final class FocusPathNodeQueries {
  private FocusPathNodeQueries() {}

  static @Nullable AccessibilityNodeInfoCompat parent(AccessibilityNodeInfoCompat node) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      // The snapshot walks all ancestors, so fetching those is useful. Siblings' subtrees are not.
      return node.getParent(AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS);
    }
    return node.getParent();
  }

  static @Nullable AccessibilityNode child(AccessibilityNode parent, int index) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      // Index verification and sibling text need this child, not its descendants.
      return AccessibilityNode.takeOwnership(parent.getCompat().getChild(index, 0));
    }
    return parent.getChild(index);
  }
}
