/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor.voicecommands

import java.text.Normalizer
import java.util.Locale

/** Full-name matching for installed apps. */
internal object VoiceAppMatching {
  private val whitespace = Regex("[\\s\\p{Z}]+")

  fun normalize(text: CharSequence?): String =
    Normalizer.normalize(text?.toString().orEmpty(), Normalizer.Form.NFKC)
      .lowercase(Locale.ROOT)
      .replace(whitespace, " ")
      .trim()
      .trimEnd('.', ',', '!', '?', ':', ';', '،', '؟', '؛')
      .trimEnd()

  /** Only "Open <name>" is an app request. */
  fun appName(command: String): String? {
    val normalized = normalize(command)
    return when {
      normalized.startsWith("open ") -> normalized.substring(5).trim()
      else -> null
    }
  }

  /** Full-name Levenshtein similarity, from 0 to 100; never a substring/partial match. */
  fun similarity(first: String, second: String): Double {
    val left = normalize(first).codePoints().toArray()
    val right = normalize(second).codePoints().toArray()
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.size + 1) { it }
    var current = IntArray(right.size + 1)
    for (i in left.indices) {
      current[0] = i + 1
      for (j in right.indices) {
        current[j + 1] =
          minOf(
            current[j] + 1,
            previous[j + 1] + 1,
            previous[j] + if (left[i] == right[j]) 0 else 1,
          )
      }
      val swap = previous
      previous = current
      current = swap
    }
    val length = maxOf(left.size, right.size)
    return 100.0 * (length - previous[right.size]) / length
  }

  /** Chooses the highest score at or above 85%, refusing ties between different apps. */
  fun <T> bestApp(
    name: String,
    apps: List<T>,
    identity: (T) -> Any? = { it },
    labels: (T) -> List<String>,
  ): T? {
    if (normalize(name).isEmpty()) return null
    var best: T? = null
    var bestScore = 85.0
    var tied = false
    for (app in apps) {
      val score = labels(app).maxOfOrNull { similarity(name, it) } ?: 0.0
      if (score < bestScore) continue
      if (score > bestScore || best == null) {
        best = app
        bestScore = score
        tied = false
      } else if (identity(app) != identity(best)) {
        tied = true
      }
    }
    return if (tied) null else best
  }
}
