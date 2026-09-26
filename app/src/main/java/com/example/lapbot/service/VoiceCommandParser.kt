package com.example.lapbot.service

internal enum class RaceVoiceCommand {
  Gaps,
  SpeakMore,
  SpeakLess,
  SectorsOn,
  SectorsOff,
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
  return when {
    GAP_COMMANDS.matches(commandText) -> RaceVoiceCommand.Gaps
    SPEAK_MORE_COMMANDS.matches(commandText) -> RaceVoiceCommand.SpeakMore
    SPEAK_LESS_COMMANDS.matches(commandText) -> RaceVoiceCommand.SpeakLess
    SECTORS_ON_COMMANDS.matches(commandText) -> RaceVoiceCommand.SectorsOn
    SECTORS_OFF_COMMANDS.matches(commandText) -> RaceVoiceCommand.SectorsOff
    else -> null
  }
}

private fun List<String>.matches(commandText: String): Boolean =
  any { it == commandText || commandText.startsWith("$it ") }

// Android's general recognizer commonly renders the made-up wake word as these real-word
// neighbours. A production keyword model will detect the audio directly and will not need them.
private val WAKE_PHRASES = listOf("lapbot", "lap bot", "lab bot", "laptop", "lapbox", "lap box")
private val GAP_COMMANDS = listOf("gaps", "gap", "what are my gaps", "cars around me")
private val SPEAK_MORE_COMMANDS = listOf("speak more", "more coaching")
private val SPEAK_LESS_COMMANDS = listOf("speak less", "less coaching")
private val SECTORS_ON_COMMANDS = listOf("sectors on", "sector times on")
private val SECTORS_OFF_COMMANDS = listOf("sectors off", "sector times off")
