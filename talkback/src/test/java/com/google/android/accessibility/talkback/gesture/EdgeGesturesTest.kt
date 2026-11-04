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

package com.google.android.accessibility.talkback.gesture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeGesturesTest {

  @Test
  fun edgeAt_findsBothEdges() {
    assertEquals(Edge.LEFT, EdgeGestureSettings.edgeAt(0f, WIDTH, EDGE))
    assertEquals(Edge.LEFT, EdgeGestureSettings.edgeAt(EDGE - 1, WIDTH, EDGE))
    assertNull(EdgeGestureSettings.edgeAt(EDGE, WIDTH, EDGE))
    assertNull(EdgeGestureSettings.edgeAt(WIDTH / 2, WIDTH, EDGE))
    assertEquals(Edge.RIGHT, EdgeGestureSettings.edgeAt(WIDTH - EDGE, WIDTH, EDGE))
    assertEquals(Edge.RIGHT, EdgeGestureSettings.edgeAt(WIDTH - 1, WIDTH, EDGE))
  }

  @Test
  fun settingsScreens_useTheKeysThatAreRead() {
    for (edge in Edge.entries) {
      val xml = File("src/main/res/xml/edge_gestures_${edge.prefName}_preferences.xml").readText()
      for (gesture in EdgeGesture.entries) {
        val key = EdgeGestureSettings.gestureKey(edge, gesture)
        assertTrue("$key missing", xml.contains("android:key=\"$key\""))
      }
    }
    val gestures = File("src/main/res/xml/gesture_preferences.xml").readText()
    assertTrue(gestures.contains("android:key=\"${EdgeGestureSettings.PREF_ENABLED}\""))
    assertTrue(gestures.contains("android:key=\"${EdgeGestureSettings.PREF_WIDTH}\""))
  }

  private companion object {
    const val WIDTH = 1000f
    const val EDGE = 50f
  }
}
