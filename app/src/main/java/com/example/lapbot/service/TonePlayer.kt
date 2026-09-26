package com.example.lapbot.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal class TonePlayer(private val scope: CoroutineScope) {
  private var playbackJob: Job? = null

  fun play(sequence: ToneSequence, onComplete: () -> Unit = {}) {
    playbackJob?.cancel()
    playbackJob =
      scope.launch(Dispatchers.IO) {
        val samples = render(sequence)
        val track =
          AudioTrack.Builder()
            .setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            ).setAudioFormat(
              AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            ).setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
          track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
          track.play()
          delay(sequence.durationsMs.sumOf { it.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) } + GAP_MS * 4L + 100)
        } finally {
          runCatching { track.stop() }
          track.release()
          scope.launch { onComplete() }
        }
      }
  }

  fun stop() {
    playbackJob?.cancel()
    playbackJob = null
  }

  private fun render(sequence: ToneSequence): ShortArray {
    val durations = sequence.durationsMs.map { it.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS) }
    val toneSamples = durations.map { it * SAMPLE_RATE / 1_000 }
    val gapSamples = GAP_MS * SAMPLE_RATE / 1_000
    val output = ShortArray(toneSamples.sum() + gapSamples * (sequence.frequenciesHz.size - 1))
    var outputIndex = 0
    sequence.frequenciesHz.forEachIndexed { toneIndex, frequency ->
      val count = toneSamples[toneIndex]
      val fadeSamples = min(FADE_MS * SAMPLE_RATE / 1_000, count / 2)
      repeat(count) { sampleIndex ->
        val envelope =
          when {
            sampleIndex < fadeSamples -> sampleIndex.toDouble() / fadeSamples
            sampleIndex >= count - fadeSamples -> (count - sampleIndex - 1).toDouble() / fadeSamples
            else -> 1.0
          }
        output[outputIndex++] =
          (sin(2.0 * PI * frequency * sampleIndex / SAMPLE_RATE) * envelope * Short.MAX_VALUE * AMPLITUDE).toInt().toShort()
      }
      if (toneIndex < sequence.frequenciesHz.lastIndex) outputIndex += gapSamples
    }
    return output
  }

  private companion object {
    const val SAMPLE_RATE = 44_100
    const val GAP_MS = 75
    const val FADE_MS = 8
    const val MIN_DURATION_MS = 50
    const val MAX_DURATION_MS = 1_000
    const val AMPLITUDE = 0.35
  }
}
