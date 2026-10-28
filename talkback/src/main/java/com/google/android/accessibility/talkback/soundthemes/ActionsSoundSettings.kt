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

package com.google.android.accessibility.talkback.soundthemes

import android.content.SharedPreferences

/**
 * When the actions available sound plays for an item that has more actions: as the item gets focus,
 * as in current versions of iOS, or as the "Actions available" hint starts, as in older ones.
 */
object ActionsSoundSettings {
  const val PREF_TIMING = "pref_actions_sound_timing"
  const val ON_FOCUS = "focus"

  const val WITH_HINT = "hint"

  /** The sound plays as the "Actions available" hint starts, not when the item gets focus. */
  @JvmStatic
  fun isWithHint(prefs: SharedPreferences): Boolean =
    prefs.getString(PREF_TIMING, WITH_HINT) == WITH_HINT
}
