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

package com.google.android.accessibility.utils.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.TtsSpan
import com.google.android.accessibility.utils.output.FailoverTextToSpeech.SpeechParam
import java.io.File

/**
 * Short speech made by a second connection to the engine, which makes the audio as fast as it can
 * rather than as it plays. So the engine is soon free to make what the next swipe will most likely
 * say, which the swipe then plays without waiting for the engine.
 *
 * Audio is only reused for the same text, voice, rate and pitch. Whatever is not ready is made when
 * it's asked for. Android writes the audio to a file as well, so this gives it /dev/null. Text that
 * tells the engine how to say it, with a TtsSpan, is left to the main connection, as the text here
 * is plain.
 */
object PreparedAudio {
  /** The longest text spoken this way. Its audio is made at once and has to fit in a stream. */
  private const val MAX_TEXT = 200
  private const val MAX_BYTES = 8_000_000
  private const val MAX_BYTES_OF_ONE = 2_000_000

  private class Making(val key: String, var stream: LowLatencyAudio.SpeechStream?) {
    var rate = 0
    var encoding = 0
    var channels = 0
    var size = 0
    var started = false
    val chunks = ArrayList<ByteArray>()
    val ranges = ArrayList<IntArray>()
  }

  private val handler by lazy { Handler(HandlerThread("PreparedAudio").apply { start() }.looper) }
  private val playHandler by lazy {
    Handler(HandlerThread("PreparedAudioPlay").apply { start() }.looper)
  }
  private val lock = Any()
  private val kept = PreparedAudioCache(MAX_BYTES)
  private val making = LinkedHashMap<String, Making>()

  // Only used on the handler's thread.
  private var client: TextToSpeech? = null
  private var clientEngine: String? = null
  private var clientVoice: Voice? = null
  private var nextId = 0

  @Volatile private var readyEngine: String? = null
  // What speech was last spoken with, which what is prepared must sound the same as.
  private var lastVoice: Voice? = null
  private var lastParams: Bundle? = null

  /** Whether this can say [text], connecting to [engine] for later if it's not yet connected. */
  @JvmStatic
  fun canSpeak(context: Context, engine: String?, voice: Voice?, text: CharSequence): Boolean {
    if (engine == null || voice == null) return false
    if (readyEngine != engine) {
      val appContext = context.applicationContext
      handler.post { connect(appContext, engine) }
      return false
    }
    return canMake(text)
  }

  private fun canMake(text: CharSequence): Boolean =
    text.length <= MAX_TEXT &&
      !(text is Spanned && text.getSpans(0, text.length, TtsSpan::class.java).isNotEmpty())

  private fun connect(context: Context, engine: String) {
    if (clientEngine == engine) return
    // Engines can give their voices the same names, and the voice last used is the old engine's.
    disconnect()
    clientEngine = engine
    client =
      TextToSpeech(
          context,
          { status -> if (status == TextToSpeech.SUCCESS) readyEngine = engine },
          engine,
        )
        .apply { setOnUtteranceProgressListener(listener) }
  }

  /** Makes speech for [text] with the voice and settings of the last speech. */
  @JvmStatic
  fun prepare(text: CharSequence) {
    if (!canMake(text)) return
    val string = text.toString()
    handler.post {
      val voice: Voice
      val params: Bundle
      synchronized(lock) {
        voice = lastVoice ?: return@post
        params = lastParams ?: return@post
        val key = keyFor(string, voice, params)
        if (kept.contains(key) || making.values.any { it.key == key }) return@post
      }
      make(string, voice, params, /* stream= */ null)
    }
  }

  /** Says [text] on [stream], with what is made or being made if it can. */
  @JvmStatic
  fun speak(
    text: CharSequence,
    voice: Voice,
    bundle: Bundle,
    stream: LowLatencyAudio.SpeechStream,
    flush: Boolean,
  ) {
    val string = text.toString()
    // The stream applies these, and an engine could as well.
    val params =
      Bundle(bundle).apply {
        remove(SpeechParam.VOLUME)
        remove(TextToSpeech.Engine.KEY_PARAM_PAN)
      }
    synchronized(lock) {
      lastVoice = voice
      lastParams = params
      val key = keyFor(string, voice, params)
      val audio = kept.get(key)
      if (audio != null) {
        playHandler.post { play(audio, stream) }
        return
      }
      // Audio past the size limit is not kept, so an item that passed it can't be played whole.
      val item =
        making.values.firstOrNull {
          it.key == key && it.started && it.stream == null && it.size <= MAX_BYTES_OF_ONE
        }
      if (item != null) {
        stream.begin(item.rate, item.encoding, item.channels)
        replay(item.chunks, item.ranges, stream)
        item.stream = stream
        return
      }
    }
    if (flush) stopMaking()
    handler.post { make(string, voice, params, stream) }
  }

  /** Stops what is being made, so that the engine is free for speech that's wanted now. */
  @JvmStatic
  fun stopMaking() {
    // Forgotten at once rather than on the handler's thread, or speech that follows could join an
    // item that is then dropped, and wait for audio that never comes.
    val wasMaking = synchronized(lock) { making.isNotEmpty().also { making.clear() } }
    handler.post {
      if (wasMaking || synchronized(lock) { making.isNotEmpty() }) client?.stop()
    }
  }

  /** Disconnects from the engine and forgets all speech. */
  @JvmStatic
  fun shutdown() {
    handler.post { disconnect() }
  }

  private fun disconnect() {
    client?.shutdown()
    client = null
    clientEngine = null
    clientVoice = null
    readyEngine = null
    synchronized(lock) {
      kept.clear()
      making.clear()
      lastVoice = null
      lastParams = null
    }
  }

  private fun play(audio: PreparedAudioCache.Audio, stream: LowLatencyAudio.SpeechStream) {
    stream.begin(audio.rate, audio.encoding, audio.channels)
    replay(audio.chunks, audio.ranges, stream)
    stream.end()
  }

  /** Gives [stream] audio made already, with its word ranges in their places among the chunks. */
  private fun replay(
    chunks: List<ByteArray>,
    ranges: List<IntArray>,
    stream: LowLatencyAudio.SpeechStream,
  ) {
    var range = 0
    for (index in 0..chunks.size) {
      while (range < ranges.size && ranges[range][0] <= index) {
        val (_, start, end, frame) = ranges[range++]
        stream.range(start, end, frame)
      }
      if (index < chunks.size) stream.write(chunks[index])
    }
  }

  private fun keyFor(text: String, voice: Voice, params: Bundle): String =
    "${voice.name}/${params.getInt(SpeechParam.RATE)}/${params.getInt(SpeechParam.PITCH)}/$text"

  private fun make(
    text: String,
    voice: Voice,
    params: Bundle,
    stream: LowLatencyAudio.SpeechStream?,
  ) {
    val tts = client
    if (tts == null || readyEngine == null) {
      stream?.fail()
      return
    }
    if (voice != clientVoice) {
      // Audio in another voice would be kept as this voice's.
      if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
        clientVoice = null
        stream?.fail()
        return
      }
      clientVoice = voice
    }
    val id = "prepared${nextId++}"
    synchronized(lock) { making[id] = Making(keyFor(text, voice, params), stream) }
    if (tts.synthesizeToFile(text, params, File("/dev/null"), id) != TextToSpeech.SUCCESS) {
      synchronized(lock) { making.remove(id) }
      stream?.fail()
    }
  }

  private val listener =
    object : UtteranceProgressListener() {
      override fun onStart(id: String) {}

      override fun onBeginSynthesis(id: String, rate: Int, format: Int, channels: Int) {
        synchronized(lock) {
          val item = making[id] ?: return
          item.rate = rate
          item.encoding = format
          item.channels = channels
          item.started = true
          item.stream?.begin(rate, format, channels)
        }
      }

      override fun onAudioAvailable(id: String, audio: ByteArray) {
        synchronized(lock) {
          val item = making[id] ?: return
          item.stream?.write(audio)
          item.size += audio.size
          if (item.size <= MAX_BYTES_OF_ONE) item.chunks += audio
        }
      }

      override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
        synchronized(lock) {
          val item = making[id] ?: return
          item.stream?.range(start, end, frame)
          item.ranges += intArrayOf(item.chunks.size, start, end, frame)
        }
      }

      override fun onDone(id: String) {
        synchronized(lock) {
          val item = making.remove(id) ?: return
          item.stream?.end()
          if (item.chunks.isNotEmpty() && item.size <= MAX_BYTES_OF_ONE) {
            kept.put(
              item.key,
              PreparedAudioCache.Audio(
                item.rate,
                item.encoding,
                item.channels,
                item.chunks,
                item.ranges,
              ),
            )
          }
        }
      }

      @Deprecated("Deprecated in Java")
      override fun onError(id: String) {
        synchronized(lock) { making.remove(id)?.stream?.fail() }
      }

      override fun onStop(id: String, interrupted: Boolean) {
        synchronized(lock) { making.remove(id)?.stream?.end() }
      }
    }
}

/** Recently made speech by its key, keeping at most [maxBytes] of audio. */
internal class PreparedAudioCache(private val maxBytes: Int) {
  /** The engine's audio for one text. Each range is its chunk, start, end and frame. */
  class Audio(
    val rate: Int,
    val encoding: Int,
    val channels: Int,
    val chunks: List<ByteArray>,
    val ranges: List<IntArray>,
  ) {
    val size = chunks.sumOf { it.size }
  }

  private val audio = LinkedHashMap<String, Audio>(16, 0.75f, /* accessOrder= */ true)
  private var bytes = 0

  fun contains(key: String): Boolean = audio.containsKey(key)

  fun get(key: String): Audio? = audio[key]

  fun put(key: String, value: Audio) {
    audio.put(key, value)?.let { bytes -= it.size }
    bytes += value.size
    val eldest = audio.entries.iterator()
    while (bytes > maxBytes && eldest.hasNext()) {
      bytes -= eldest.next().value.size
      eldest.remove()
    }
  }

  fun clear() {
    audio.clear()
    bytes = 0
  }
}
