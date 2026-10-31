/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.android.accessibility.talkback.compositor

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.compositor.rule.EventTypeNotificationStateChangedFeedbackRule
import com.google.android.accessibility.talkback.compositor.rule.HeadsUpNotificationAppearedFeedbackRule
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.monitor.CollectionState
import com.google.android.accessibility.utils.monitor.InputModeTracker
import com.google.android.accessibility.utils.output.SpeechController
import java.util.Locale
import java.util.function.Function
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSpeechInterruptionTest {
  class Service : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
  }

  private lateinit var service: Service
  private lateinit var globals: GlobalVariables

  @Before
  fun setUp() {
    service = Robolectric.setupService(Service::class.java)
    FormFactorUtils.initialize(service)
    globals = GlobalVariables(service, InputModeTracker(), CollectionState(), null)
    globals.setUserPreferredLocale(Locale.ENGLISH)
  }

  @Test
  fun notificationSpeechCanBeReplacedByGestureSpeech() {
    val rules = mutableMapOf<Int, Function<Compositor.HandleEventOptions, EventFeedback>>()
    EventTypeNotificationStateChangedFeedbackRule.addFeedbackRule(rules, service, globals)
    val notification = Notification().apply {
      category = Notification.CATEGORY_MESSAGE
      extras = Bundle().apply {
        putCharSequence("android.title", "Message")
        putCharSequence("android.text", "A long notification")
      }
    }
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED).apply {
      parcelableData = notification
    }

    val feedback =
      rules.getValue(Compositor.EVENT_TYPE_NOTIFICATION_STATE_CHANGED)
        .apply(Compositor.HandleEventOptions().`object`(event))

    assertEquals(SpeechController.QUEUE_MODE_INTERRUPT, feedback.queueMode())
  }

  @Test
  fun headsUpNotificationSpeechCanBeReplacedByGestureSpeech() {
    val rules = mutableMapOf<Int, Function<Compositor.HandleEventOptions, EventFeedback>>()
    HeadsUpNotificationAppearedFeedbackRule.addFeedbackRule(rules, service, globals)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

    val feedback =
      rules.getValue(Compositor.EVENT_HEADS_UP_NOTIFICATION_APPEARED)
        .apply(Compositor.HandleEventOptions().`object`(event))

    assertEquals(SpeechController.QUEUE_MODE_INTERRUPT, feedback.queueMode())
  }
}
