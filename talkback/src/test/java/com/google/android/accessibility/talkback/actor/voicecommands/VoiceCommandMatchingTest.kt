/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandMatchingTest {
  @Test
  fun onlyOpenFollowedByANameIsAnAppRequest() {
    assertEquals("google home", VoiceCommandMatching.appName("  OPEN   Google Home! "))
    assertNull(VoiceCommandMatching.appName("Open"))
    assertNull(VoiceCommandMatching.appName("Openly"))
    assertNull(VoiceCommandMatching.appName("reopen Camera"))
    assertNull(VoiceCommandMatching.appName("please open Camera"))
  }

  @Test
  fun similarityIncludesExactly85PercentButExcludesLowerScores() {
    val name = "abcdefghijklmnopqrst"
    val atThreshold = "xxxdefghijklmnopqrst"
    val belowThreshold = "xxxxefghijklmnopqrst"
    assertEquals(85.0, VoiceCommandMatching.similarity(name, atThreshold), 0.0)
    assertEquals(
      atThreshold,
      VoiceCommandMatching.bestApp(name, listOf(atThreshold)) { listOf(it) },
    )
    assertNull(VoiceCommandMatching.bestApp(name, listOf(belowThreshold)) { listOf(it) })
    assertNull(VoiceCommandMatching.bestApp("", listOf(name)) { listOf(it) })
  }

  @Test
  fun theClosestWholeNameWinsRegardlessOfOrder() {
    for (apps in listOf(listOf("WhatsApp", "WhatsApps"), listOf("WhatsApps", "WhatsApp"))) {
      assertEquals("WhatsApp", VoiceCommandMatching.bestApp("whatsapp", apps) { listOf(it) })
    }
    assertEquals(
      "WhatsApp",
      VoiceCommandMatching.bestApp("whatsap", listOf("WhatsApp")) { listOf(it) },
    )
    assertNull(VoiceCommandMatching.bestApp("YouTube", listOf("YouTube Music")) { listOf(it) })
  }

  @Test
  fun equallyGoodNamesAndDuplicateLabelsAreAmbiguous() {
    assertNull(
      VoiceCommandMatching.bestApp("abcdefghij", listOf("abcdefghiX", "abcdefghiY")) { listOf(it) }
    )
    assertNull(
      VoiceCommandMatching.bestApp("Camera", listOf("package.one", "package.two")) {
        listOf("Camera")
      }
    )
  }

  @Test
  fun aButtonNeedsItsEntireLabelAndNotASimilarWord() {
    assertTrue(VoiceCommandMatching.sameLabel("  SAVE\u00a0 changes! ", "Save changes"))
    assertFalse(VoiceCommandMatching.sameLabel("Save", "Save changes"))
    assertFalse(VoiceCommandMatching.sameLabel("Delete", "Deleted"))
    assertFalse(VoiceCommandMatching.sameLabel("", ""))
    assertFalse(VoiceCommandMatching.sameLabel("Save", null))
  }

  @Test
  fun normalizationIsIndependentOfTheDevicesCaseRules() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      assertTrue(VoiceCommandMatching.sameLabel("INPUT", "input"))
      assertEquals(100.0, VoiceCommandMatching.similarity("Ｃａｍｅｒａ", "camera"), 0.0)
      assertTrue(VoiceCommandMatching.sameLabel("حفظ؟", "حفظ"))
    } finally {
      Locale.setDefault(original)
    }
  }
}
