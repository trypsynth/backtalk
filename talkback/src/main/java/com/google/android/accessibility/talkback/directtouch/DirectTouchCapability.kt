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

import android.content.pm.PackageManager
import android.os.Bundle

/** What an app asks for in its manifest, on its application or its launch activity. */
data class DirectTouchCapability(
  /** It sets [DirectTouchSettings.CAPABILITY_KEY], for direct touch on the whole screen. */
  val wholeScreen: Boolean,
  /** It sets [DirectTouchSettings.REGIONS_CAPABILITY_KEY], for direct touch on the views it marks. */
  val regions: Boolean,
) {
  /** Whether it asks for direct touch at all, so that it is turned on the first time it's seen. */
  val any: Boolean
    get() = wholeScreen || regions

  /** What either this or [other] asks for. */
  operator fun plus(other: DirectTouchCapability): DirectTouchCapability =
    DirectTouchCapability(wholeScreen || other.wholeScreen, regions || other.regions)

  companion object {
    val NONE = DirectTouchCapability(wholeScreen = false, regions = false)

    /** What the app asks for, in its application or launch activity meta-data. */
    fun of(pm: PackageManager, packageName: String): DirectTouchCapability {
      try {
        val app = fromMetaData(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData)
        if (app.wholeScreen && app.regions) {
          return app
        }
        val component = pm.getLaunchIntentForPackage(packageName)?.component ?: return app
        return app + fromMetaData(pm.getActivityInfo(component, PackageManager.GET_META_DATA).metaData)
      } catch (_: Exception) {
        return NONE
      }
    }

    fun fromMetaData(metaData: Bundle?): DirectTouchCapability =
      DirectTouchCapability(
        wholeScreen = metaData?.getBoolean(DirectTouchSettings.CAPABILITY_KEY, false) == true,
        regions = metaData?.getBoolean(DirectTouchSettings.REGIONS_CAPABILITY_KEY, false) == true,
      )
  }
}
