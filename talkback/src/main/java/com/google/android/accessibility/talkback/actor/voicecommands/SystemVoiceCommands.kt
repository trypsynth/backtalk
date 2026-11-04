/*
 * Copyright (C) 2026 The Android Open Source Project
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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor.voicecommands

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor.RECOGNITION_SPEECH_DELAY_MS
import com.google.android.accessibility.talkback.monitor.BatteryMonitor
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.talkback.status.StatusReader
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SettingsUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

/**
 * Handles system-level voice commands such as:
 * - Directly opening System Settings ("open settings")
 * - Opening Backtalk settings ("open backtalk settings")
 * - Toggling Do Not Disturb ("turn on/off dnd")
 * - Device status and battery inquiries ("battery", "status", "time")
 * - Volume adjustments ("volume up", "volume down", "mute", "unmute")
 * - Finding a control on screen ("find play button")
 */
class SystemVoiceCommands(
  private val context: Context,
  private var pipeline: Pipeline.FeedbackReturner? = null,
  var batteryMonitor: BatteryMonitor? = null,
  var statusReader: StatusReader? = null
) {

  fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
    this.pipeline = pipeline
  }

  /**
   * Attempts to handle the spoken command as a system or app command.
   * Returns true if the command was recognized and handled.
   */
  fun handleCommand(command: String, isPartialResult: Boolean): Boolean {
    val norm = VoiceCommandFuzzyMatcher.normalize(command)
    if (norm.isBlank()) return false

    // 1. Backtalk Settings (requires explicit mention of Backtalk or TalkBack)
    if (handleBacktalkSettings(norm)) {
      return true
    }

    // 2. System Settings ("open settings", "settings")
    if (handleSystemSettings(norm)) {
      return true
    }

    // 3. Do Not Disturb
    if (handleDoNotDisturb(norm)) {
      return true
    }

    // 4. Battery & Device Status
    if (handleStatusAndBattery(norm)) {
      return true
    }

    // 5. Volume Controls
    if (handleVolume(norm)) {
      return true
    }

    // 6. Find / Focus on screen element ("find play button", "focus search", "locate cancel")
    if (handleFindOnScreen(norm, command)) {
      return true
    }

    return false
  }

  // --- Sub-handlers ---

  /**
   * Handles opening Backtalk settings. Strictly requires that the user said
   * "backtalk" or "talkback" so generic "settings" or "open settings" does not trigger this.
   */
  private fun handleBacktalkSettings(norm: String): Boolean {
    if (!norm.contains("backtalk") && !norm.contains("talkback")) {
      return false
    }

    val targets = listOf(
      "open backtalk settings",
      "backtalk settings",
      "backtalk setting",
      "open talkback settings",
      "talkback settings",
      "talkback setting"
    )
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, targets, threshold = 0.85)) {
      if (SettingsUtils.allowLinksOutOfSettings(context)) {
        speakDelayed(context.getString(R.string.voice_commands_opening_backtalk_settings))
        val intent = Intent(context, TalkBackPreferencesActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      }
      return true
    }
    return false
  }

  /**
   * Handles opening the device's System Settings app when the user says "open settings",
   * "settings", "system settings", or "device settings".
   */
  private fun handleSystemSettings(norm: String): Boolean {
    val settingsTargets = listOf(
      "open settings",
      "settings",
      "launch settings",
      "start settings",
      "go to settings",
      "system settings",
      "device settings"
    )
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, settingsTargets, threshold = 0.88)) {
      speakDelayed(context.getString(R.string.voice_commands_opening_settings))
      val intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      return true
    }
    return false
  }

  private fun handleDoNotDisturb(norm: String): Boolean {
    val isOn = dndDirection(norm) ?: return false

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    if (notificationManager != null) {
      if (notificationManager.isNotificationPolicyAccessGranted) {
        if (isOn) {
          notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
          speakDelayed(context.getString(R.string.voice_commands_dnd_on))
        } else {
          notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
          speakDelayed(context.getString(R.string.voice_commands_dnd_off))
        }
      } else {
        speakDelayed(context.getString(R.string.voice_commands_dnd_permission_required))
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      }
      return true
    }
    return false
  }

  private fun handleStatusAndBattery(norm: String): Boolean {
    val batteryTargets = listOf("battery", "battery level", "how much battery", "check battery", "battery status")
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, batteryTargets, threshold = 0.85)) {
      val desc = batteryMonitor?.batteryStateDescription
      if (!desc.isNullOrBlank()) {
        speakDelayed(desc)
        return true
      }
    }

    val statusTargets = listOf("status", "device status", "what time is it", "time", "check status")
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, statusTargets, threshold = 0.85)) {
      val desc = statusReader?.describe()
      if (!desc.isNullOrBlank()) {
        speakDelayed(desc)
        return true
      }
    }

    return false
  }

  private fun handleVolume(norm: String): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

    val isVolUp = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("volume up", "increase volume", "louder", "turn volume up"), threshold = 0.85)
    val isVolDown = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("volume down", "decrease volume", "quieter", "turn volume down", "lower volume"), threshold = 0.85)
    val isMute = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("mute", "mute volume", "mute audio"), threshold = 0.85)
    val isUnmute = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("unmute", "unmute volume", "unmute audio"), threshold = 0.85)

    if (isVolUp) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
      announceCurrentVolume(audioManager)
      return true
    }
    if (isVolDown) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
      announceCurrentVolume(audioManager)
      return true
    }
    if (isMute) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
      speakDelayed(context.getString(R.string.voice_commands_muted))
      return true
    }
    if (isUnmute) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
      speakDelayed(context.getString(R.string.voice_commands_unmuted))
      return true
    }

    return false
  }

  private fun announceCurrentVolume(audioManager: AudioManager) {
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val pct = if (max > 0) (current * 100) / max else 0
    speakDelayed(context.getString(R.string.voice_commands_volume_set, pct))
  }

  private fun speakDelayed(text: CharSequence) {
    val returner = pipeline ?: return
    val speakOptions = SpeakOptions.create().apply {
      setFlags(
        FeedbackItem.FLAG_NO_HISTORY
          or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
      )
    }
    val feedback = Feedback.speech(text, speakOptions).setDelayMs(RECOGNITION_SPEECH_DELAY_MS)
    returner.returnFeedback(EVENT_ID_UNTRACKED, feedback)
  }

  // --- Screen Actions: Click and Find ---

  private val FIND_PREFIX_REGEX = Regex(
    "^(?:find|search\\s+for|search|locate|focus)(?:\\s+on)?(?:\\s+the)?\\s+(.+)$",
    RegexOption.IGNORE_CASE
  )

  private val ROLE_SUFFIXES = listOf(
    "button", "btn", "tab", "switch", "checkbox", "check box", "toggle",
    "icon", "image", "link", "menu", "option", "item", "header", "heading",
    "slider", "field", "text field", "input", "card"
  )

  private data class TargetSpec(
    val rawTarget: String,
    val baseTarget: String,
    val expectedRole: String?
  )

  private fun parseTarget(rawRemainder: String): TargetSpec {
    var clean = rawRemainder.trim()
    if (clean.startsWith("the ", ignoreCase = true)) {
      clean = clean.substring(4).trim()
    }
    for (role in ROLE_SUFFIXES) {
      if (clean.endsWith(" $role", ignoreCase = true)) {
        val base = clean.substring(0, clean.length - role.length - 1).trim()
        if (base.isNotBlank()) {
          return TargetSpec(clean, base, role.lowercase())
        }
      }
    }
    return TargetSpec(clean, clean, null)
  }

  private fun handleFindOnScreen(normInput: String, rawInput: String): Boolean {
    val match = FIND_PREFIX_REGEX.matchEntire(normInput) ?: return false
    val rawTarget = match.groupValues[1].trim()
    if (rawTarget.length < 2) return false

    val spec = parseTarget(rawTarget)
    val candidate = findBestNodeOnScreen(spec)
    if (candidate == null) {
      speakDelayed(context.getString(R.string.voice_commands_element_not_found, spec.rawTarget))
      return true
    }

    candidate.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    speakDelayed(context.getString(R.string.voice_commands_found, candidate.label))
    return true
  }

  private data class NodeCandidate(
    val node: AccessibilityNodeInfo,
    val score: Double,
    val label: String
  )

  private fun findBestNodeOnScreen(spec: TargetSpec): NodeCandidate? {
    val accessService = context as? AccessibilityService ?: return null
    val root = accessService.rootInActiveWindow
      ?: accessService.windows.firstOrNull {
        it.isFocused || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
      }?.root
      ?: return null

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var inspected = 0
    var bestCandidate: NodeCandidate? = null

    val targetFull = spec.rawTarget.lowercase()
    val targetBase = spec.baseTarget.lowercase()
    val expectedRole = spec.expectedRole

    while (queue.isNotEmpty() && inspected < 1000) {
      val node = queue.removeFirst()
      inspected++

      val isVisible = node.isVisibleToUser
      if (isVisible) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.trim()
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable || node.parent?.isClickable == true
        val isCheckable = node.isCheckable

        val label = when {
          !desc.isNullOrBlank() -> desc
          !text.isNullOrBlank() -> text
          !viewId.isNullOrBlank() -> viewId
          else -> null
        }

        if (!label.isNullOrBlank()) {
          val normLabel = label.lowercase()
          var score = 0.0

          // Exact matches
          if (normLabel == targetFull) {
            score = 1.0
          } else if (normLabel == targetBase) {
            score = 0.95
          } else if (normLabel.startsWith(targetFull) || normLabel.endsWith(targetFull)) {
            score = 0.85
          } else if (normLabel.startsWith(targetBase) || normLabel.endsWith(targetBase)) {
            score = 0.80
          } else if (normLabel.contains(targetFull)) {
            score = 0.75
          } else if (normLabel.contains(targetBase)) {
            score = 0.70
          } else {
            val sim = maxOf(
              VoiceCommandFuzzyMatcher.similarity(targetFull, normLabel),
              VoiceCommandFuzzyMatcher.similarity(targetBase, normLabel)
            )
            if (sim >= 0.72) {
              score = sim * 0.85
            }
          }

          if (score > 0.0) {
            // Role bonus
            if (expectedRole != null) {
              val matchesRole = when (expectedRole) {
                "button", "btn" -> className.contains("Button", ignoreCase = true) || isClickable
                "switch", "toggle", "checkbox", "check box" -> isCheckable || className.contains("Switch", ignoreCase = true)
                "tab" -> className.contains("Tab", ignoreCase = true)
                "image", "icon" -> className.contains("Image", ignoreCase = true)
                "field", "input", "text field" -> node.isEditable || className.contains("Edit", ignoreCase = true)
                else -> false
              }
              if (matchesRole) {
                score += 0.15
              }
            }

            if (isClickable) score += 0.10
            if (isVisible) score += 0.05

            if (bestCandidate == null || score > bestCandidate.score) {
              bestCandidate = NodeCandidate(node, score, label)
            }
          }
        }
      }

      for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
          queue.add(child)
        }
      }
    }

    return bestCandidate?.takeIf { it.score >= 0.65 }
  }
}

private val DND_ON_TARGETS = listOf(
  "turn on dnd",
  "turn on do not disturb",
  "enable dnd",
  "enable do not disturb",
  "dnd on",
  "do not disturb on"
)
private val DND_OFF_TARGETS = listOf(
  "turn off dnd",
  "turn off do not disturb",
  "disable dnd",
  "disable do not disturb",
  "dnd off",
  "do not disturb off"
)

private val DND_OFF_WORDS = setOf("off", "disable")
private val DND_ON_WORDS = setOf("on", "enable")

/**
 * Tells whether [norm] asks to turn Do Not Disturb on (true) or off (false), or null when it is
 * not about Do Not Disturb. The on and off phrases differ by a word or two, so a fuzzy match
 * alone finds both; the word itself decides.
 */
internal fun dndDirection(norm: String): Boolean? {
  val fuzzyOn = VoiceCommandFuzzyMatcher.matchesAny(norm, DND_ON_TARGETS, threshold = 0.85)
  val fuzzyOff = VoiceCommandFuzzyMatcher.matchesAny(norm, DND_OFF_TARGETS, threshold = 0.85)
  if (!fuzzyOn && !fuzzyOff) return null
  val words = norm.split(" ")
  val saysOff = words.any { it in DND_OFF_WORDS }
  val saysOn = words.any { it in DND_ON_WORDS }
  return when {
    // Both words, such as "turn on dnd and switch off the ringer": too unclear to act on.
    saysOff && saysOn -> null
    saysOff -> false
    saysOn -> true
    // No clear word: only act when exactly one list matches.
    fuzzyOn != fuzzyOff -> fuzzyOn
    else -> null
  }
}
