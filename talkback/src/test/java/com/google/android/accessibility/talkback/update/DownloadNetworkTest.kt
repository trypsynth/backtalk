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

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** Wi-Fi for an update download on a watch: used when it comes, and always released. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNetworkTest {
  private lateinit var connectivity: ConnectivityManager
  private lateinit var downloadNetwork: DownloadNetwork
  private val wifi: Network = ShadowNetwork.newInstance(7)

  /** The networks that downloads were started with, null for the default network. */
  private val downloads = mutableListOf<Network?>()

  @Before
  fun setUp() {
    connectivity =
      RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
    downloadNetwork = DownloadNetwork(connectivity, Handler(Looper.getMainLooper()))
  }

  /** The requests that keep Wi-Fi on. */
  private fun requests(): Set<ConnectivityManager.NetworkCallback> =
    shadowOf(connectivity).networkCallbacks

  private fun wait(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  private fun acquire(): ConnectivityManager.NetworkCallback {
    downloadNetwork.acquire { downloads.add(it) }
    return requests().single()
  }

  @Test
  fun wifiThatComesUpIsUsedAndKeptUntilTheDownloadEnds() {
    acquire().onAvailable(wifi)

    assertEquals(listOf<Network?>(wifi), downloads)
    assertEquals(1, requests().size)
    downloadNetwork.release()
    assertTrue(requests().isEmpty())
    downloadNetwork.release()
  }

  @Test
  fun withoutWifiTheUpdateDownloadsOverThePhoneAfterTwentySeconds() {
    acquire()

    wait(Duration.ofSeconds(19))
    assertTrue(downloads.isEmpty())
    wait(Duration.ofSeconds(1))
    assertEquals(listOf<Network?>(null), downloads)
    assertTrue(requests().isEmpty())
  }

  @Test
  fun wifiThatComesTooLateDoesNotStartAnotherDownload() {
    val request = acquire()

    wait(Duration.ofSeconds(20))
    request.onAvailable(wifi)
    assertEquals(listOf<Network?>(null), downloads)
  }

  @Test
  fun whenAndroidHasNoWifiToOfferTheDownloadStartsAtOnce() {
    acquire().onUnavailable()

    assertEquals(listOf<Network?>(null), downloads)
    // Android has released the request itself, so it isn't released a second time.
    assertEquals(1, requests().size)
    wait(Duration.ofSeconds(20))
    assertEquals(1, downloads.size)
  }

  @Test
  fun wifiIsReleasedAfterTenMinutesEvenIfTheDownloadStillRuns() {
    acquire().onAvailable(wifi)

    wait(Duration.ofMinutes(10).minusSeconds(1))
    assertEquals(1, requests().size)
    wait(Duration.ofSeconds(1))
    assertTrue(requests().isEmpty())
  }

  @Test
  fun stoppingWhileWaitingDownloadsOverThePhoneAtOnce() {
    acquire()

    downloadNetwork.stopWaiting()
    assertEquals(listOf<Network?>(null), downloads)
    assertTrue(requests().isEmpty())
  }

  @Test
  fun stoppingDuringTheDownloadKeepsItsWifi() {
    acquire().onAvailable(wifi)

    downloadNetwork.stopWaiting()
    assertEquals(listOf<Network?>(wifi), downloads)
    assertEquals(1, requests().size)
  }

  @Test
  fun anotherWifiNetworkDoesNotStartAnotherDownload() {
    val request = acquire()

    request.onAvailable(wifi)
    request.onAvailable(ShadowNetwork.newInstance(8))
    assertEquals(listOf<Network?>(wifi), downloads)
  }
}
