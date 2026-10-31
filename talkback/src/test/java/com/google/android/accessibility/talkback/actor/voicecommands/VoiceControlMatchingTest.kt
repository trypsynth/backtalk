/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VoiceControlMatchingTest {
  @Test
  fun namesMustMatchTheWholeLabelWithoutFuzzyMatching() {
    assertTrue(VoiceControlMatching.sameLabel(" SAVE\u00a0 changes! ", "Save changes"))
    assertFalse(VoiceControlMatching.sameLabel("Save", "Save changes"))
    assertFalse(VoiceControlMatching.sameLabel("Delete", "Deleted"))
    assertFalse(VoiceControlMatching.sameLabel("", ""))
    assertFalse(VoiceControlMatching.sameLabel("Save", null))
  }

  @Test
  fun unicodeAndCaseNormalizationDoNotDependOnTheDeviceLocale() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      assertTrue(VoiceControlMatching.sameLabel("INPUT", "input"))
      assertTrue(VoiceControlMatching.sameLabel("\uff33\uff41\uff56\uff45", "Save"))
      assertTrue(VoiceControlMatching.sameLabel("\u062d\u0641\u0638\u061f", "\u062d\u0641\u0638"))
    } finally {
      Locale.setDefault(original)
    }
  }
}
