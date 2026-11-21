/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VoiceAppMatchingTest {
  @Test
  fun onlyOpenFollowedByANameIsAnAppRequest() {
    assertEquals("google home", VoiceAppMatching.appName("  OPEN   Google Home! "))
    assertNull(VoiceAppMatching.appName("Open"))
    assertNull(VoiceAppMatching.appName("Openly"))
    assertNull(VoiceAppMatching.appName("reopen Camera"))
    assertNull(VoiceAppMatching.appName("please open Camera"))
  }

  @Test
  fun similarityIncludesExactly85PercentButExcludesLowerScores() {
    val name = "abcdefghijklmnopqrst"
    val atThreshold = "xxxdefghijklmnopqrst"
    val belowThreshold = "xxxxefghijklmnopqrst"
    assertEquals(85.0, VoiceAppMatching.similarity(name, atThreshold), 0.0)
    assertEquals(
      atThreshold,
      VoiceAppMatching.bestApp(name, listOf(atThreshold)) { listOf(it) },
    )
    assertNull(VoiceAppMatching.bestApp(name, listOf(belowThreshold)) { listOf(it) })
    assertNull(VoiceAppMatching.bestApp("", listOf(name)) { listOf(it) })
  }

  @Test
  fun theClosestWholeNameWinsRegardlessOfOrder() {
    for (apps in listOf(listOf("WhatsApp", "WhatsApps"), listOf("WhatsApps", "WhatsApp"))) {
      assertEquals("WhatsApp", VoiceAppMatching.bestApp("whatsapp", apps) { listOf(it) })
    }
    assertEquals(
      "WhatsApp",
      VoiceAppMatching.bestApp("whatsap", listOf("WhatsApp")) { listOf(it) },
    )
    assertNull(VoiceAppMatching.bestApp("YouTube", listOf("YouTube Music")) { listOf(it) })
  }

  @Test
  fun equallyGoodNamesAndDuplicateLabelsAreAmbiguous() {
    assertNull(
      VoiceAppMatching.bestApp("abcdefghij", listOf("abcdefghiX", "abcdefghiY")) { listOf(it) }
    )
    assertNull(
      VoiceAppMatching.bestApp("Camera", listOf("package.one", "package.two")) {
        listOf("Camera")
      }
    )
  }

  @Test
  fun normalizationIsIndependentOfTheDevicesCaseRules() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      assertEquals(100.0, VoiceAppMatching.similarity("INPUT", "input"), 0.0)
      assertEquals(100.0, VoiceAppMatching.similarity("Ｃａｍｅｒａ", "camera"), 0.0)
      assertEquals("\u062d\u0641\u0638", VoiceAppMatching.normalize("\u062d\u0641\u0638\u061f"))
    } finally {
      Locale.setDefault(original)
    }
  }
}
