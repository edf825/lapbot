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
  fun `recognises coaching detail commands`() {
    assertEquals(RaceVoiceCommand.SpeakMore, parseRaceVoiceCommand("Lapbot, speak more"))
    assertEquals(RaceVoiceCommand.SpeakMore, parseRaceVoiceCommand("lap bot more coaching please"))
    assertEquals(RaceVoiceCommand.SpeakLess, parseRaceVoiceCommand("LAPBOT SPEAK LESS"))
    assertEquals(RaceVoiceCommand.SpeakLess, parseRaceVoiceCommand("lapbox less coaching please"))
  }

  @Test
  fun `recognises sector timing toggle commands`() {
    assertEquals(RaceVoiceCommand.SectorsOn, parseRaceVoiceCommand("Lapbot, sectors on"))
    assertEquals(RaceVoiceCommand.SectorsOn, parseRaceVoiceCommand("lap bot sector times on please"))
    assertEquals(RaceVoiceCommand.SectorsOff, parseRaceVoiceCommand("LAPBOT SECTORS OFF"))
    assertEquals(RaceVoiceCommand.SectorsOff, parseRaceVoiceCommand("lapbox sector times off please"))
  }

  @Test
  fun `command without wake word is ignored`() {
    assertNull(parseRaceVoiceCommand("gaps"))
    assertNull(parseRaceVoiceCommand("speak more"))
  }

  @Test
  fun `unknown command is ignored`() {
    assertNull(parseRaceVoiceCommand("Lapbot weather"))
  }
}
