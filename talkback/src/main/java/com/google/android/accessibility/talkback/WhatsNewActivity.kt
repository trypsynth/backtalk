/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.accessibility.talkback.update.UpdateChecker
import java.util.concurrent.Executors

/** Shows the changes in the latest Backtalk development build. */
class WhatsNewActivity : Activity() {
  private val executor = Executors.newSingleThreadExecutor()
  private lateinit var status: TextView
  private lateinit var progress: ProgressBar
  private lateinit var changes: TextView
  private lateinit var retry: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = getString(R.string.title_whats_new)

    val padding = (resources.displayMetrics.density * 24).toInt()
    val column =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding)
      }
    status = TextView(this)
    progress = ProgressBar(this)
    changes = TextView(this)
    retry = Button(this).apply { setText(R.string.whats_new_retry) }
    retry.setOnClickListener { load() }
    val scroll = ScrollView(this).apply { addView(changes) }
    column.addView(status)
    column.addView(progress)
    column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    column.addView(retry)
    setContentView(column)
    load()
  }

  override fun onDestroy() {
    executor.shutdownNow()
    super.onDestroy()
  }

  private fun load() {
    status.setText(R.string.whats_new_loading)
    progress.visibility = View.VISIBLE
    changes.visibility = View.GONE
    retry.visibility = View.GONE
    executor.execute {
      try {
        val notes = UpdateChecker.checkLatest(applicationContext)
        runOnUiThread {
          status.text = getString(R.string.whats_new_build, notes.build)
          changes.text =
            notes.changes.joinToString("\n\n").ifEmpty {
              getString(R.string.whats_new_no_changes)
            }
          progress.visibility = View.GONE
          changes.visibility = View.VISIBLE
        }
      } catch (_: Exception) {
        runOnUiThread {
          status.setText(R.string.whats_new_error)
          progress.visibility = View.GONE
          retry.visibility = View.VISIBLE
        }
      }
    }
  }

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, WhatsNewActivity::class.java)
  }
}
