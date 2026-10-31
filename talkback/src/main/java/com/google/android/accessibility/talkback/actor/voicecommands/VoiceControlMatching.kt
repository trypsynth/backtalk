/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import java.text.Normalizer
import java.util.Locale

/** Spoken controls must match their complete accessible label; no fuzzy matching is used. */
internal object VoiceControlMatching {
  private val whitespace = Regex("[\\s\\p{Z}]+")

  fun normalize(text: CharSequence?): String =
    Normalizer.normalize(text?.toString().orEmpty(), Normalizer.Form.NFKC)
      .lowercase(Locale.ROOT)
      .replace(whitespace, " ")
      .trim()
      .trimEnd('.', ',', '!', '?', ':', ';', '\u060c', '\u061f', '\u061b')
      .trimEnd()

  fun sameLabel(command: String, label: CharSequence?): Boolean {
    val normalized = normalize(command)
    return normalized.isNotEmpty() && normalized == normalize(label)
  }
}
