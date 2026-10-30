/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityRecord

/** Counts source retrievals without changing the platform's prefetch policy. */
@Implements(AccessibilityRecord::class)
class ScrollSourceRecordShadow : ShadowAccessibilityRecord() {
  var sourceReads = 0
    private set

  @Implementation
  protected override fun getSource(): AccessibilityNodeInfo? {
    sourceReads++
    return super.getSource()
  }
}
