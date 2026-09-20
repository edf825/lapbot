package com.example.lapbot.service

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class VoiceCommandParserTest {
  @Test
  fun `recognises wake word followed by gaps command`() {
    assertEquals(RaceVoiceCommand.Gaps, parseRaceVoiceCommand("Lapbot, gaps"))
    assertEquals(RaceVoiceCommand.Gaps, parseRaceVoiceCommand("lap bot what are my gaps"))
    assertEquals(RaceVoiceCommand.Gaps, parseRaceVoiceCommand("lab bot gap please"))
    assertEquals(RaceVoiceCommand.Gaps, parseRaceVoiceCommand("Laptop gaps"))
    assertEquals(RaceVoiceCommand.Gaps, parseRaceVoiceCommand("Lapbox gaps"))
  }

  @Test
  fun `command without wake word is ignored`() {
    assertNull(parseRaceVoiceCommand("gaps"))
  }

  @Test
  fun `unknown command is ignored`() {
    assertNull(parseRaceVoiceCommand("Lapbot weather"))
  }
}
