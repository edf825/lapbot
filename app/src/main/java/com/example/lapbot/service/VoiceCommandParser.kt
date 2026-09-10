package com.example.lapbot.service

internal enum class RaceVoiceCommand {
  Gaps,
}

internal fun parseRaceVoiceCommand(transcript: String): RaceVoiceCommand? {
  val normalized =
    transcript.lowercase()
      .replace(Regex("[^a-z0-9 ]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  val commandText =
    WAKE_PHRASES.firstNotNullOfOrNull { wakePhrase ->
      val wakeIndex = normalized.indexOf(wakePhrase)
      if (wakeIndex >= 0) normalized.substring(wakeIndex + wakePhrase.length).trim() else null
    } ?: return null
  return if (GAP_COMMANDS.any { it == commandText || commandText.startsWith("$it ") }) {
    RaceVoiceCommand.Gaps
  } else null
}

// Android's general recognizer commonly renders the made-up wake word as these real-word
// neighbours. A production keyword model will detect the audio directly and will not need them.
private val WAKE_PHRASES = listOf("lapbot", "lap bot", "lab bot", "laptop", "lapbox", "lap box")
private val GAP_COMMANDS = listOf("gaps", "gap", "what are my gaps", "cars around me")
