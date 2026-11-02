/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record

import android.view.accessibility.AccessibilityNodeInfo
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/** Robolectric's tree fixture has no implementation of the API 33 query overloads. */
@Implements(AccessibilityNodeInfo::class)
class FocusPathQueriesNodeInfoShadow:ShadowAccessibilityNodeInfo() {
  @Implementation(minSdk=33)
  protected fun getParent(strategy:Int):AccessibilityNodeInfo?=super.getParent()
  @Implementation(minSdk=33)
  protected fun getChild(index:Int,strategy:Int):AccessibilityNodeInfo?=super.getChild(index)
}
