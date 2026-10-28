/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.directtouch

import android.content.SharedPreferences
import com.google.android.accessibility.talkback.focusmanagement.LiftToActivateMode

/**
 * Settings for direct touch: which apps get raw touch, and how Backtalk reports changes. Apps that
 * declare [CAPABILITY_KEY] or [REGIONS_CAPABILITY_KEY] are turned on the first time they are seen,
 * and after that the user's choice is kept.
 */
object DirectTouchSettings {
  /** Manifest meta-data key a game sets to ask for direct touch on the whole screen. */
  const val CAPABILITY_KEY = "dev.nvgt.capability.DIRECT_TOUCH"

  /**
   * Manifest meta-data key an app sets to ask for direct touch only on the views it marks with
   * [REGION_VIEW_ID]. The rest of the screen stays with explore by touch. When an app sets both
   * keys, this one wins.
   */
  const val REGIONS_CAPABILITY_KEY = "dev.nvgt.capability.DIRECT_TOUCH_REGIONS"

  /**
   * The name of the view ID resource that marks a view as a direct touch area, in an app that sets
   * [REGIONS_CAPABILITY_KEY]. Backtalk asks the app for its views with the ID
   * `<package>:id/dev_nvgt_direct_touch_region`, which the app answers by searching its own views,
   * so Backtalk never walks the app's tree. Several views can share it.
   */
  const val REGION_VIEW_ID = "dev_nvgt_direct_touch_region"

  const val PREF_MASTER = "pref_direct_touch_master"
  const val PREF_SPEECH = "pref_direct_touch_speech"
  const val PREF_HAPTICS = "pref_direct_touch_haptics"
  /** Whether a tap on a navigation bar button that takes touches directly says the button. */
  const val PREF_NAV_BAR_SPEECH = "pref_speak_nav_bar_buttons"
  private const val PREF_APPS = "pref_direct_touch_apps"
  private const val PREF_SEEN = "pref_direct_touch_seen"
  private const val PREF_TYPING_PREFIX = "pref_direct_touch_typing_"

  /**
   * The old "Navigation bar always direct" setting. Lift to activate on the navigation bar
   * replaced it, since that also lets a single tap press the buttons, and still says them.
   */
  private const val OLD_PREF_NAV_BAR = "pref_direct_touch_nav_bar"

  /**
   * Turns lift to activate on for the navigation bar, stored under [liftToActivateKey], for users
   * who had the old navigation bar setting on and lift to activate off. Runs once.
   */
  fun migrateNavBarSetting(prefs: SharedPreferences, liftToActivateKey: String) {
    if (!prefs.contains(OLD_PREF_NAV_BAR)) {
      return
    }
    val editor = prefs.edit().remove(OLD_PREF_NAV_BAR)
    val liftToActivate = LiftToActivateMode.fromPrefValue(prefs.getString(liftToActivateKey, null))
    if (prefs.getBoolean(OLD_PREF_NAV_BAR, false) && liftToActivate == LiftToActivateMode.DISABLED) {
      editor.putString(liftToActivateKey, LiftToActivateMode.NAVIGATION_BAR.prefValue)
    }
    editor.apply()
  }

  fun isMasterEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_MASTER, true)

  fun setMasterEnabled(prefs: SharedPreferences, enabled: Boolean) {
    prefs.edit().putBoolean(PREF_MASTER, enabled).apply()
  }

  fun isSpeechEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SPEECH, true)

  fun isHapticsEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_HAPTICS, false)

  fun isNavBarSpeechEnabled(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(PREF_NAV_BAR_SPEECH, true)

  fun isAppEnabled(prefs: SharedPreferences, pkg: String): Boolean = pkg in stringSet(prefs, PREF_APPS)

  /** Whether direct touch is on and turned on for any app, so that it can turn on at all. */
  fun hasEnabledApps(prefs: SharedPreferences): Boolean =
    isMasterEnabled(prefs) && stringSet(prefs, PREF_APPS).isNotEmpty()

  fun setAppEnabled(prefs: SharedPreferences, pkg: String, enabled: Boolean) {
    val apps = stringSet(prefs, PREF_APPS)
    prefs.edit().putStringSet(PREF_APPS, if (enabled) apps + pkg else apps - pkg).apply()
  }

  fun isDirectTyping(prefs: SharedPreferences, pkg: String): Boolean =
    prefs.getBoolean(PREF_TYPING_PREFIX + pkg, false)

  fun setDirectTyping(prefs: SharedPreferences, pkg: String, enabled: Boolean) {
    prefs.edit().putBoolean(PREF_TYPING_PREFIX + pkg, enabled).apply()
  }

  /** Whether [pkg] has been seen, after which [onFirstSight] changes nothing for it. */
  fun isSeen(prefs: SharedPreferences, pkg: String): Boolean = pkg in stringSet(prefs, PREF_SEEN)

  /**
   * Records that [pkg] has been seen. The first time, an app that [declaresCapability] is turned
   * on. Later calls change nothing, so an app the user turned off stays off.
   */
  fun onFirstSight(prefs: SharedPreferences, pkg: String, declaresCapability: Boolean) {
    val seen = stringSet(prefs, PREF_SEEN)
    if (pkg in seen) {
      return
    }
    val editor = prefs.edit().putStringSet(PREF_SEEN, seen + pkg)
    if (declaresCapability) {
      editor.putStringSet(PREF_APPS, stringSet(prefs, PREF_APPS) + pkg)
    }
    editor.apply()
  }

  private fun stringSet(prefs: SharedPreferences, key: String): Set<String> =
    prefs.getStringSet(key, emptySet()).orEmpty().toSet()
}
