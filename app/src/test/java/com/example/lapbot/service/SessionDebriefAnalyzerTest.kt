package com.example.lapbot.service

import com.example.lapbot.data.RecordedEngineerEvent
import com.example.lapbot.data.RecordedDriver
import com.example.lapbot.data.RecordedLap
import com.example.lapbot.data.RecordedSession
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class SessionDebriefAnalyzerTest {
  @Test
  fun `slow incident lap is highlighted and excluded from main pace assessment`() {
    val session =
      session(
        listOf(
          lap(1, 52_300), lap(2, 52_250), lap(3, 52_200),
          lap(4, 51_900), lap(5, 51_850), lap(6, 51_800),
          lap(7, 70_000),
        ),
      )

    val debrief = SessionDebriefAnalyzer().analyze(session)

    assertEquals(setOf(7), debrief.outlierLaps)
    assertEquals(51_800L, debrief.bestLapMs)
    assertNotNull(debrief.repeatablePaceMs)
    assertEquals(DebriefTrend.Improved, debrief.paceTrend)
    assertFalse(7 in debrief.personalBestLaps)
  }

  @Test
  fun `coherent sectors produce theoretical lap and assembly gap`() {
    val session = session(listOf(lap(1, 52_300), lap(2, 52_100), lap(3, 52_000)))

    val debrief = SessionDebriefAnalyzer().analyze(session)

    assertEquals(51_700L, debrief.theoreticalLapMs)
    assertEquals(300L, debrief.theoreticalGapMs)
  }

  @Test
  fun `missing sector and benchmark evidence remains explicitly conservative`() {
    val session = session((1..5).map { RecordedLap(it, 52_000L + it * 10) })

    val debrief = SessionDebriefAnalyzer().analyze(session)

    assertNull(debrief.theoreticalLapMs)
    assertNull(debrief.strongestOpportunitySector)
    assertTrue(debrief.conclusions.first().startsWith("Not enough reliable sector evidence"))
    assertTrue(debrief.objectiveSummary.startsWith("No Race Engineer objective"))
  }

  @Test
  fun `recorded objective confirmation is reflected in debrief`() {
    val session =
      session(listOf(lap(1, 52_300), lap(2, 52_100), lap(3, 52_000))).copy(
        engineerEvents =
          listOf(
            RecordedEngineerEvent(1, lap = 1, kind = "objective", sector = 2, status = "Working"),
            RecordedEngineerEvent(2, lap = 3, kind = "objective", sector = 2, status = "ImprovementConfirmed"),
          ),
      )

    assertTrue(SessionDebriefAnalyzer().analyze(session).objectiveSummary.contains("improved"))
  }

  @Test
  fun `debrief reuses credible field evidence for relative opportunity`() {
    val focusedLaps = (1..5).map { RecordedLap(it, 79_130, 31_930, 21_200, 26_000) }
    val benchmarkOne = (1..5).map { RecordedLap(it, 76_000, 31_000, 20_000, 25_000) }
    val benchmarkTwo = (1..5).map { RecordedLap(it, 76_150, 31_050, 20_050, 25_050) }
    val recorded =
      session(focusedLaps).copy(
        field =
          listOf(
            RecordedDriver("front-1", "Front One", "1", 1, benchmarkOne),
            RecordedDriver("front-2", "Front Two", "2", 2, benchmarkTwo),
          ),
      )

    val debrief = SessionDebriefAnalyzer().analyze(recorded)

    assertEquals(2, debrief.strongestOpportunitySector)
    assertNotNull(debrief.relativePacePercent)
    assertEquals(listOf("1", "2"), debrief.benchmarkKartNumbers)
  }

  private fun lap(number: Int, time: Long): RecordedLap {
    val sector1 = when (number) { 1 -> 20_000L; 2 -> 19_900L; else -> 20_200L }
    val sector2 = when (number) { 1 -> 15_000L; 2 -> 15_200L; else -> 14_800L }
    return RecordedLap(number, time, sector1, sector2, time - sector1 - sector2)
  }

  private fun session(laps: List<RecordedLap>) =
    RecordedSession(
      id = "session-1",
      sourceSessionKey = "race-1",
      trackId = "buckmore",
      venue = "Buckmore Park",
      provider = "Alpha Race Hub",
      startedAtEpochMs = 1,
      endedAtEpochMs = 2,
      selectedDriverId = "driver-12",
      selectedDriverName = "Jonny Reeves",
      kartNumbers = listOf("12"),
      laps = laps,
      field = emptyList(),
    )
}
