package com.example.lapbot.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuously runs Android's on-device recognizer while explicitly enabled.
 *
 * Recognition is suspended around Lapbot's own audio so the response cannot be
 * mistaken for the next command. All methods are called on the main thread.
 */
internal class VoiceCommandListener(
  private val context: Context,
  private val onCommand: (RaceVoiceCommand) -> Unit,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var enabled = false
  private var pausedForPlayback = false
  private var commandLatched = false

  fun start(): Boolean {
    if (!recognitionAvailable()) return false
    enabled = true
    commandLatched = false
    if (recognizer == null) {
      recognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
          SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
          SpeechRecognizer.createSpeechRecognizer(context)
        }.apply { setRecognitionListener(listener) }
    }
    listen()
    return true
  }

  fun stop() {
    enabled = false
    pausedForPlayback = false
    commandLatched = false
    handler.removeCallbacksAndMessages(null)
    recognizer?.cancel()
  }

  fun pauseForPlayback() {
    if (!enabled) return
    handler.removeCallbacksAndMessages(null)
    pausedForPlayback = true
    recognizer?.cancel()
  }

  fun resumeAfterPlayback() {
    if (!enabled) return
    handler.removeCallbacksAndMessages(null)
    handler.postDelayed({
      pausedForPlayback = false
      commandLatched = false
      listen()
    }, PLAYBACK_COOLDOWN_MS)
  }

  fun release() {
    stop()
    recognizer?.destroy()
    recognizer = null
  }

  private fun recognitionAvailable(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)
    } else {
      SpeechRecognizer.isRecognitionAvailable(context)
    }

  private fun listen() {
    if (!enabled || pausedForPlayback || commandLatched) return
    runCatching {
      recognizer?.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
          .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
      )
    }.onFailure {
      Log.w(TAG, "Unable to start voice recognition", it)
      restartSoon()
    }
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = Unit
      override fun onBeginningOfSpeech() = Unit
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() = Unit
      override fun onEvent(eventType: Int, params: Bundle?) = Unit

      override fun onPartialResults(partialResults: Bundle?) {
        handleResults(partialResults)
      }

      override fun onResults(results: Bundle?) {
        if (!handleResults(results)) restartSoon()
      }

      override fun onError(error: Int) {
        if (enabled && !pausedForPlayback && !commandLatched) restartSoon()
      }
    }

  private fun handleResults(bundle: Bundle?): Boolean {
    val alternatives = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    val command = alternatives.firstNotNullOfOrNull(::parseRaceVoiceCommand) ?: return false
    if (commandLatched) return true
    commandLatched = true
    recognizer?.cancel()
    onCommand(command)
    return true
  }

  private fun restartSoon() {
    handler.removeCallbacksAndMessages(null)
    handler.postDelayed(::listen, RESTART_DELAY_MS)
  }

  private companion object {
    const val TAG = "LapbotVoiceCommands"
    const val RESTART_DELAY_MS = 300L
    const val PLAYBACK_COOLDOWN_MS = 500L
  }
}
