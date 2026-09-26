package com.example.lapbot.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lapbot.service.RaceVoiceCommand
import com.example.lapbot.service.parseRaceVoiceCommand
import com.example.lapbot.theme.LapbotTheme
import java.util.Locale

/** Foreground-only experiment. Production always-listening must use a dedicated wake-word engine. */
class VoiceCommandSpikeActivity : ComponentActivity() {
  private val handler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var textToSpeech: TextToSpeech? = null
  private var toneGenerator: ToneGenerator? = null
  private var requested = false
  private var commandLatched = false
  private var status by mutableStateOf("Stopped")
  private var transcript by mutableStateOf("—")

  private val microphonePermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startSpike() else status = "Microphone permission denied"
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    textToSpeech =
      TextToSpeech(this) { result ->
        if (result == TextToSpeech.SUCCESS) textToSpeech?.language = Locale.UK
      }.also { tts ->
        tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onError(utteranceId: String) = resumeAfterResponse()
            override fun onDone(utteranceId: String) = resumeAfterResponse()
          },
        )
      }
    setContent {
      LapbotTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text("Voice command spike", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("Say: “Lapbot, gaps”", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("Status: $status")
            Text("Heard: $transcript")
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::requestStart, modifier = Modifier.fillMaxWidth()) { Text("Start listening") }
            OutlinedButton(onClick = ::stopSpike, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
          }
        }
      }
    }
  }

  private fun requestStart() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      startSpike()
    } else {
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  private fun startSpike() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
      status = "On-device Android recognition is unavailable"
      return
    }
    if (recognizer == null) {
      recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply { setRecognitionListener(listener) }
    }
    requested = true
    commandLatched = false
    listen()
  }

  private fun listen() {
    if (!requested || commandLatched) return
    status = "Listening for “Lapbot, gaps”"
    recognizer?.startListening(
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
    )
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) { status = "Ready — say “Lapbot, gaps”" }
      override fun onBeginningOfSpeech() { status = "Hearing speech" }
      override fun onRmsChanged(rmsdB: Float) = Unit
      override fun onBufferReceived(buffer: ByteArray?) = Unit
      override fun onEndOfSpeech() { status = "Recognising" }
      override fun onEvent(eventType: Int, params: Bundle?) = Unit

      override fun onPartialResults(partialResults: Bundle?) {
        handleResults(partialResults)
      }

      override fun onResults(results: Bundle?) {
        if (!handleResults(results)) restartSoon()
      }

      override fun onError(error: Int) {
        if (requested && !commandLatched) {
          status = "Recognizer reset ($error)"
          restartSoon()
        }
      }
    }

  private fun handleResults(bundle: Bundle?): Boolean {
    val alternatives = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (alternatives.isNotEmpty()) transcript = alternatives.joinToString(" | ")
    val command = alternatives.firstNotNullOfOrNull(::parseRaceVoiceCommand) ?: return false
    if (commandLatched) return true
    commandLatched = true
    recognizer?.cancel()
    execute(command)
    return true
  }

  private fun execute(command: RaceVoiceCommand) {
    status = "Command recognised: ${command.name.lowercase()}"
    toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    handler.postDelayed({ speak(commandResponse(command)) }, 220)
  }

  private fun commandResponse(command: RaceVoiceCommand): String =
    when (command) {
      RaceVoiceCommand.Gaps -> "Gap to P3, kart 12, 1 point 20. Gap to P5, kart 27, point 33"
      RaceVoiceCommand.SpeakMore -> "Coaching detail, high"
      RaceVoiceCommand.SpeakLess -> "Coaching detail, low"
      RaceVoiceCommand.SectorsOn -> "Sector times on"
      RaceVoiceCommand.SectorsOff -> "Sector times off"
    }

  private fun speak(message: String) {
    status = "Responding: $message"
    textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, RESPONSE_UTTERANCE_ID)
      ?: resumeAfterResponse()
  }

  private fun resumeAfterResponse() {
    handler.post {
      if (!requested) return@post
      status = "Cooldown"
      handler.postDelayed({
        commandLatched = false
        listen()
      }, 500)
    }
  }

  private fun restartSoon() {
    handler.postDelayed({
      if (requested && !commandLatched) listen()
    }, 300)
  }

  private fun stopSpike() {
    requested = false
    commandLatched = false
    handler.removeCallbacksAndMessages(null)
    recognizer?.cancel()
    status = "Stopped"
  }

  override fun onStop() {
    stopSpike()
    super.onStop()
  }

  override fun onDestroy() {
    stopSpike()
    recognizer?.destroy()
    recognizer = null
    textToSpeech?.shutdown()
    toneGenerator?.release()
    super.onDestroy()
  }

  private companion object {
    const val RESPONSE_UTTERANCE_ID = "voice-spike-response"
  }
}
