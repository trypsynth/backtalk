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

package com.google.android.accessibility.talkback.update

import android.content.Context
import android.net.Network
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection

/** One cancellable transfer. The commit gate prevents a late Cancel from installing an APK. */
internal class UpdateTransfer {
  private val lock = Any()
  @Volatile private var canceled = false
  private var committed = false
  private var connection: HttpURLConnection? = null

  fun cancel(): Boolean {
    val active = synchronized(lock) {
      if (committed) return false
      canceled = true
      connection
    }
    // Closing a socket can block, so never do it on the screen reader's main thread.
    if (active != null) Thread { active.disconnect() }.start()
    return true
  }

  fun checkActive() {
    if (canceled) throw InterruptedIOException("Update canceled")
  }

  fun commit(action: () -> Unit) = synchronized(lock) {
    checkActive()
    action()
    committed = true
  }

  fun download(context: Context, url: String, target: File, network: Network?, progress: (Long, Long) -> Unit) {
    download(target, { UpdateChecker.openConnection(context, url, network) }, progress)
  }

  fun download(
    target: File,
    open: () -> HttpURLConnection,
    progress: (Long, Long) -> Unit,
  ) {
    checkActive()
    val source = open()
    synchronized(lock) { connection = source }
    try {
      checkActive()
      if (source.responseCode != HttpURLConnection.HTTP_OK) {
        throw IOException("Download returned HTTP ${source.responseCode}")
      }
      val total = source.contentLengthLong
      var bytes = 0L
      progress(bytes, total)
      source.inputStream.use { input ->
        target.outputStream().use { output ->
          val buffer = ByteArray(32 * 1024)
          while (true) {
            checkActive()
            val count = input.read(buffer)
            checkActive()
            if (count == -1) break
            output.write(buffer, 0, count)
            bytes += count
            progress(bytes, total)
          }
        }
      }
      if (bytes == 0L || (total >= 0 && bytes != total)) {
        throw IOException("Incomplete update: $bytes of $total bytes")
      }
      checkActive()
    } catch (e: Exception) {
      target.delete()
      throw e
    } finally {
      synchronized(lock) { connection = null }
      source.disconnect()
    }
  }
}
