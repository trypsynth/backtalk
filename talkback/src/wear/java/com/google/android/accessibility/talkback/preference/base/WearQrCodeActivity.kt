/*
 * Copyright (C) 2023 Google Inc.
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
@file:Suppress("PackageName")

package com.google.android.accessibility.talkback.preference.base

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.google.android.accessibility.material.theme.AccessibilitySuiteTheme
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.compose.screen.WearQrCodeScreen
import com.google.android.accessibility.talkback.compose.viewmodel.WearQrCodeViewModel

/** A fragment in compliance with wear material style to show qr code. */
class WearQrCodeActivity : ComponentActivity() {

  private val wearQRCodeViewModel: WearQrCodeViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContent {
      AccessibilitySuiteTheme { WearQrCodeScreen(qrCodeViewModel = wearQRCodeViewModel) }
    }

    intent.extras?.let {
      wearQRCodeViewModel.renderQrCode(
        title = it.getString(EXTRA_TITLE, ""),
        ctaText = it.getString(EXTRA_CALL_TO_ACTION, ""),
        alternate = it.getString(EXTRA_ALTERNATE_STRING, ""),
        url = it.getString(EXTRA_URL),
        sizePx = resources.getDimensionPixelSize(R.dimen.wear_qr_code_size),
      )
    }
  }

  companion object {
    private const val EXTRA_URL = "extra_url"
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_CALL_TO_ACTION = "extra_call_to_action"
    private const val EXTRA_ALTERNATE_STRING = "extra_alternate_string"

    @JvmStatic
    fun startWearQrCodeActivity(
      activity: Activity,
      url: String?,
      title: String?,
      callToAction: String?,
      alternateString: String?,
    ) {
      val intent = Intent(activity, WearQrCodeActivity::class.java)
      intent.putExtra(EXTRA_URL, url)
      intent.putExtra(EXTRA_TITLE, title)
      intent.putExtra(EXTRA_CALL_TO_ACTION, callToAction)
      intent.putExtra(EXTRA_ALTERNATE_STRING, alternateString)
      activity.startActivity(intent)
    }
  }
}
