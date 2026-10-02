package com.example.lapbot.service

internal sealed interface RaceVoiceCommand {
  data object Help : RaceVoiceCommand
  data object Gaps : RaceVoiceCommand
  data object SpeakMore : RaceVoiceCommand
  data object SpeakLess : RaceVoiceCommand
  data object SectorsOn : RaceVoiceCommand
  data object SectorsOff : RaceVoiceCommand
  data object VolumeUp : RaceVoiceCommand
  data object VolumeDown : RaceVoiceCommand
  data class Volume(val level: Int) : RaceVoiceCommand
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
  val volumeMatch = VOLUME_COMMAND.matchEntire(commandText)
  return when {
    VOLUME_UP_COMMAND.matches(commandText) -> RaceVoiceCommand.VolumeUp
    VOLUME_DOWN_COMMAND.matches(commandText) -> RaceVoiceCommand.VolumeDown
    volumeMatch != null -> RaceVoiceCommand.Volume(parseVolumeLevel(volumeMatch.groupValues[1]))
    HELP_COMMANDS.matches(commandText) -> RaceVoiceCommand.Help
    GAP_COMMANDS.matches(commandText) -> RaceVoiceCommand.Gaps
    SPEAK_MORE_COMMANDS.matches(commandText) -> RaceVoiceCommand.SpeakMore
    SPEAK_LESS_COMMANDS.matches(commandText) -> RaceVoiceCommand.SpeakLess
    SECTORS_ON_COMMANDS.matches(commandText) -> RaceVoiceCommand.SectorsOn
    SECTORS_OFF_COMMANDS.matches(commandText) -> RaceVoiceCommand.SectorsOff
    else -> null
  }
}

internal const val VOICE_COMMAND_HELP_RESPONSE =
  "Say Lapbot, then gaps, speak more, speak less, sectors on, sectors off, volume followed by a number from zero to ten, volume up, volume down, help, or commands."

private fun parseVolumeLevel(value: String): Int = value.toIntOrNull() ?: VOLUME_WORDS.indexOf(value)

private fun List<String>.matches(commandText: String): Boolean =
  any { it == commandText || commandText.startsWith("$it ") }

// Android's general recognizer commonly renders the made-up wake word as these real-word
// neighbours. A production keyword model will detect the audio directly and will not need them.
private val WAKE_PHRASES = listOf("lapbot", "lap bot", "lab bot", "laptop", "lapbox", "lap box")
private val VOLUME_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
private val VOLUME_COMMAND = Regex("^(?:set )?volume (10|[0-9]|${VOLUME_WORDS.joinToString("|")})(?: please)?$")
private val VOLUME_UP_COMMAND = Regex("^(?:turn )?volume up(?: please)?$")
private val VOLUME_DOWN_COMMAND = Regex("^(?:turn )?volume down(?: please)?$")
private val HELP_COMMANDS = listOf("help", "commands")
private val GAP_COMMANDS = listOf("gaps", "gap", "what are my gaps", "cars around me")
private val SPEAK_MORE_COMMANDS = listOf("speak more", "more coaching")
private val SPEAK_LESS_COMMANDS = listOf("speak less", "less coaching")
private val SECTORS_ON_COMMANDS = listOf("sectors on", "sector times on")
private val SECTORS_OFF_COMMANDS = listOf("sectors off", "sector times off")
