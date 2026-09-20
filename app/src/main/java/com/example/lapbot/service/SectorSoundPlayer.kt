package com.example.lapbot.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.lapbot.R

internal enum class SectorSound {
  Standard,
  Best,
}

internal class SectorSoundPlayer(private val context: Context) {
  private var player: MediaPlayer? = null

  fun play(sound: SectorSound) {
    player?.release()
    val created = MediaPlayer.create(context, sound.resourceId, audioAttributes(), 0)
    if (created == null) return
    player =
      created.apply {
        setOnCompletionListener { completed ->
          completed.release()
          if (player === completed) player = null
        }
        setOnErrorListener { failed, _, _ ->
          failed.release()
          if (player === failed) player = null
          true
        }
        start()
      }
  }

  fun release() {
    player?.release()
    player = null
  }

  private fun audioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
}

private val SectorSound.resourceId: Int
  get() =
    when (this) {
      SectorSound.Standard -> R.raw.sector
      SectorSound.Best -> R.raw.best_sector
    }
