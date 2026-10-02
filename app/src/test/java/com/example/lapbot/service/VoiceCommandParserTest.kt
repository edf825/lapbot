package com.example.lapbot.service

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
  @Test
  fun `recognises help and commands after wake word`() {
    assertEquals(RaceVoiceCommand.Help, parseRaceVoiceCommand("Lapbot, help"))
    assertEquals(RaceVoiceCommand.Help, parseRaceVoiceCommand("lap bot commands please"))
    assertEquals(RaceVoiceCommand.Help, parseRaceVoiceCommand("Laptop commands"))
  }

  @Test
  fun `help response lists every available command`() {
    listOf("gaps", "speak more", "speak less", "sectors on", "sectors off", "volume", "volume up", "volume down", "help", "commands")
      .forEach { assertTrue(VOICE_COMMAND_HELP_RESPONSE.contains(it)) }
  }

  @Test
  fun `recognises volume levels as digits and words`() {
    (0..10).forEach { level ->
      assertEquals(RaceVoiceCommand.Volume(level), parseRaceVoiceCommand("Lapbot, volume $level"))
    }
    assertEquals(RaceVoiceCommand.Volume(5), parseRaceVoiceCommand("lap bot volume five please"))
    assertEquals(RaceVoiceCommand.Volume(10), parseRaceVoiceCommand("LAPBOT SET VOLUME TEN"))
  }

  @Test
  fun `recognises relative volume commands`() {
    assertEquals(RaceVoiceCommand.VolumeUp, parseRaceVoiceCommand("Lapbot, volume up"))
    assertEquals(RaceVoiceCommand.VolumeDown, parseRaceVoiceCommand("lap bot turn volume down please"))
  }

  @Test
  fun `ignores invalid volume requests`() {
    assertNull(parseRaceVoiceCommand("volume 5"))
    assertNull(parseRaceVoiceCommand("Lapbot volume"))
    assertNull(parseRaceVoiceCommand("Lapbot volume 11"))
    assertNull(parseRaceVoiceCommand("Lapbot volume eleven"))
    assertNull(parseRaceVoiceCommand("Lapbot volume minus one"))
    assertNull(parseRaceVoiceCommand("Lapbot volume 5 6"))
    assertNull(parseRaceVoiceCommand("Lapbot volume up 3"))
  }

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
    assertNull(parseRaceVoiceCommand("help"))
    assertNull(parseRaceVoiceCommand("commands"))
    assertNull(parseRaceVoiceCommand("gaps"))
    assertNull(parseRaceVoiceCommand("speak more"))
  }

  @Test
  fun `unknown command is ignored`() {
    assertNull(parseRaceVoiceCommand("Lapbot weather"))
  }
}
