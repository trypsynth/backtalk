/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 */

package com.google.android.accessibility.talkback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.google.android.accessibility.talkback.update.UpdateChecker
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.utils.material.A11yAlertDialogWrapper
import java.util.concurrent.Executors

/** Shows the changes introduced by the update that brought the current Backtalk build. */
class WhatsNewActivity : FragmentActivity() {
  private val executor = Executors.newSingleThreadExecutor()
  private var dialog: A11yAlertDialogWrapper? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    load()
  }

  override fun onDestroy() {
    dialog?.dismiss()
    executor.shutdownNow()
    super.onDestroy()
  }

  private fun load() {
    showDialog(getString(R.string.update_checking), showOk = false)
    executor.execute {
      try {
        val notes =
          UpdateChecker.checkLatest(
            applicationContext,
            Updater.whatsNewFromHash(applicationContext),
          )
        val message =
          getString(R.string.summary_pref_check_for_updates, notes.build) +
            "\n\n" +
            notes.changes.joinToString("\n\n").ifEmpty {
              getString(R.string.update_up_to_date)
            }
        runOnUiThread { showDialog(message, showOk = true) }
      } catch (_: Exception) {
        runOnUiThread { showDialog(getString(R.string.update_check_failed), showOk = true) }
      }
    }
  }

  private fun showDialog(message: String, showOk: Boolean) {
    if (isFinishing || isDestroyed) {
      return
    }
    dialog?.dismiss()
    val builder =
      A11yAlertDialogWrapper.materialDialogBuilder(this, supportFragmentManager)
        .setTitle(R.string.new_feature_in_talkback_title)
        .setMessage(message)
        .setCancelable(showOk)
    if (showOk) {
      builder.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
    }
    dialog = builder.create().also { it.show() }
  }

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, WhatsNewActivity::class.java)
  }
}
