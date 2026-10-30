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

package com.google.android.accessibility.talkback.preference.base

import android.content.Context
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.accessibility.material.preference.AccessibilitySeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.EdgeGestureSettings
import java.text.NumberFormat

/** The width of the screen edges. The slider counts quarters of a centimeter, and says cm. */
class EdgeWidthPreference(context: Context, attrs: AttributeSet?) :
  AccessibilitySeekBarPreference(context, attrs) {

  init {
    min = EdgeGestureSettings.MIN_WIDTH_QUARTERS
    max = EdgeGestureSettings.MAX_WIDTH_QUARTERS
    seekBarIncrement = 1
    showSeekBarValue = false
    setDefaultValue(EdgeGestureSettings.DEFAULT_WIDTH_QUARTERS)
    updatesContinuously = true
    setOnPreferenceChangeListener { _, newValue ->
      // Setting the summary would rebind the row mid-drag.
      showWidth(newValue as Int)
      true
    }
  }

  private var seekBar: SeekBar? = null
  private var summaryView: TextView? = null

  override fun getSummary(): CharSequence = widthText(value)

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    seekBar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar
    summaryView = holder.findViewById(android.R.id.summary) as? TextView
    showWidth(value)
  }

  private fun showWidth(quarters: Int) {
    val text = widthText(quarters)
    summaryView?.text = text
    seekBar?.let { ViewCompat.setStateDescription(it, text) }
  }

  private fun widthText(quarters: Int): String {
    val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
    return context.getString(R.string.template_edge_width_cm, format.format(quarters / 4.0))
  }
}
