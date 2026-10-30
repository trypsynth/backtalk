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

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateTransferTest {
  private lateinit var server: HttpServer
  private lateinit var directory: File
  private val data = ByteArray(300_000) { (it * 31).toByte() }

  @Before fun setUp() {
    directory = Files.createTempDirectory("update-transfer").toFile()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/known") { exchange ->
      exchange.sendResponseHeaders(200, data.size.toLong())
      exchange.responseBody.use { it.write(data) }
    }
    server.createContext("/unknown") { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.use { it.write(data) }
    }
    server.createContext("/missing") { exchange ->
      exchange.sendResponseHeaders(404, -1)
      exchange.close()
    }
    server.start()
  }

  @After fun tearDown() {
    server.stop(0)
    directory.deleteRecursively()
  }

  private fun connection(path: String): HttpURLConnection =
    (URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection).apply {
      connectTimeout = 2000
      readTimeout = 2000
    }

  @Test fun reportsRealProgressAndDownloadsTheWholeApk() {
    val target = File(directory, "update.apk")
    val progress = mutableListOf<Pair<Long, Long>>()
    UpdateTransfer().download(target, { connection("/known") }) { bytes, total -> progress.add(bytes to total) }
    assertArrayEquals(data, target.readBytes())
    assertEquals(0L to data.size.toLong(), progress.first())
    assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
    assertTrue(progress.zipWithNext().all { (a, b) -> b.first > a.first })
  }

  @Test fun downloadsWithoutContentLength() {
    val target = File(directory, "update.apk")
    var reportedTotal = 0L
    UpdateTransfer().download(target, { connection("/unknown") }) { _, total -> reportedTotal = total }
    assertEquals(-1L, reportedTotal)
    assertArrayEquals(data, target.readBytes())
  }

  @Test fun httpFailureDeletesExistingPartialFile() {
    val target = File(directory, "update.apk").apply { writeText("partial") }
    assertThrows(IOException::class.java) {
      UpdateTransfer().download(target, { connection("/missing") }) { _, _ -> }
    }
    assertFalse(target.exists())
  }

  @Test fun cancellationDeletesPartialFileAndPreventsCommit() {
    val task = UpdateTransfer()
    val target = File(directory, "update.apk")
    assertThrows(IOException::class.java) {
      task.download(target, { connection("/known") }) { bytes, _ ->
        if (bytes > 0) task.cancel()
      }
    }
    assertFalse(target.exists())
    var installed = false
    assertThrows(IOException::class.java) { task.commit { installed = true } }
    assertFalse(installed)
  }

  @Test fun cancellationBeforeConnectingDoesNotOpenTheNetwork() {
    val task = UpdateTransfer()
    task.cancel()
    assertThrows(IOException::class.java) {
      task.download(File(directory, "update.apk"), { error("Must not connect") }) { _, _ -> }
    }
  }

  @Test fun committedInstallationCannotBeCanceled() {
    val task = UpdateTransfer()
    var installed = false
    task.commit { installed = true }
    assertTrue(installed)
    assertFalse(task.cancel())
  }

  @Test fun cancelDisconnectsABlockedReadWithoutBlockingTheCaller() {
    val reading = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val task = UpdateTransfer()
    val target = File(directory, "update.apk")
    val source = object : HttpURLConnection(URL("http://localhost")) {
      override fun connect() {}
      override fun disconnect() { disconnected.countDown() }
      override fun usingProxy() = false
      override fun getResponseCode() = 200
      override fun getContentLengthLong() = -1L
      override fun getInputStream() = object : java.io.InputStream() {
        override fun read(): Int {
          reading.countDown()
          check(disconnected.await(2, TimeUnit.SECONDS))
          return -1
        }
      }
    }
    val executor = Executors.newSingleThreadExecutor()
    try {
      val work = executor.submit<Boolean> {
        try {
          task.download(target, { source }) { _, _ -> }
          false
        } catch (e: IOException) { true }
      }
      assertTrue(reading.await(2, TimeUnit.SECONDS))
      assertTrue(task.cancel())
      assertTrue(work.get(2, TimeUnit.SECONDS))
      assertFalse(target.exists())
    } finally {
      task.cancel()
      executor.shutdownNow()
    }
  }

  @Test fun incompleteResponseIsRejectedAndDeleted() {
    val target = File(directory, "update.apk")
    val source = object : HttpURLConnection(URL("http://localhost")) {
      override fun connect() {}
      override fun disconnect() {}
      override fun usingProxy() = false
      override fun getResponseCode() = 200
      override fun getContentLengthLong() = 1000L
      override fun getInputStream() = byteArrayOf(1, 2, 3).inputStream()
    }
    assertThrows(IOException::class.java) { UpdateTransfer().download(target, { source }) { _, _ -> } }
    assertFalse(target.exists())
  }
}
