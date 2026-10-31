package com.google.android.accessibility.utils.output

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedAudioCacheTest {
  private fun audio(bytes: Int) =
    PreparedAudioCache.Audio(24000, 2, 1, listOf(ByteArray(bytes)), emptyList())

  @Test
  fun keepsAudioThatFits() {
    val cache = PreparedAudioCache(maxBytes = 100)
    val first = audio(40)
    cache.put("a", first)
    cache.put("b", audio(40))

    assertSame(first, cache.get("a"))
    assertTrue(cache.contains("b"))
  }

  @Test
  fun dropsTheLeastRecentlyUsedWhenFull() {
    val cache = PreparedAudioCache(maxBytes = 100)
    cache.put("a", audio(40))
    cache.put("b", audio(40))
    cache.get("a")
    cache.put("c", audio(40))

    assertTrue(cache.contains("a"))
    assertFalse(cache.contains("b"))
    assertTrue(cache.contains("c"))
  }

  @Test
  fun replacingAudioFreesWhatItUsed() {
    val cache = PreparedAudioCache(maxBytes = 100)
    cache.put("a", audio(80))
    cache.put("a", audio(80))
    cache.put("b", audio(20))

    assertTrue(cache.contains("a"))
    assertTrue(cache.contains("b"))
  }

  @Test
  fun clearForgetsEverything() {
    val cache = PreparedAudioCache(maxBytes = 100)
    cache.put("a", audio(80))
    cache.clear()
    cache.put("b", audio(90))

    assertFalse(cache.contains("a"))
    assertTrue(cache.contains("b"))
  }
}
