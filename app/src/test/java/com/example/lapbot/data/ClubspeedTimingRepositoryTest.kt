package com.example.lapbot.data

import java.io.IOException
import java.net.SocketTimeoutException
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ClubspeedTimingRepositoryTest {
  private val json = Json

  @Test
  fun completedHubInvocationTimeoutCannotTearDownAConnectedStream() {
    assertEquals(
      true,
      shouldIgnoreClubspeedInvocationFailure(ConnectionStatus.Connected, SocketTimeoutException("timeout")),
    )
    assertFalse(
      shouldIgnoreClubspeedInvocationFailure(ConnectionStatus.Connecting, SocketTimeoutException("timeout")),
    )
    assertFalse(shouldIgnoreClubspeedInvocationFailure(ConnectionStatus.Connected, IOException("closed")))
  }

  @Test
  fun capturedDaytonaGpPayloadMapsLapOnlyTiming() {
    val accumulator = ClubspeedTimingAccumulator()

    val rows = accumulator.apply(scoreboard(lap = 39, lapTime = "53.157", bestLapTime = "52.926"))

    val row = rows.single()
    assertEquals("clubspeed:83370:1293214:1", row.id)
    assertEquals("clubspeed:83370", accumulator.sessionKey)
    assertEquals("1", row.number)
    assertEquals("Anonymous", row.name)
    assertEquals(1, row.position)
    assertEquals(0L, row.gapToLeaderMs)
    assertEquals(39, row.gapRecordedAtLap)
    assertEquals(39, row.lap)
    assertEquals(53_157L, row.lapMs)
    assertEquals(52_926L, row.bestLapMs)
    assertNull(row.sector1Ms)
    assertNull(row.theoreticalBestMs)
    assertEquals(listOf(LapHistoryEntry(39, 53_157L)), row.lapHistory)
  }

  @Test
  fun subsequentPushesBuildLapHistoryWithoutInventingSectors() {
    val accumulator = ClubspeedTimingAccumulator()
    accumulator.apply(scoreboard(lap = 39, lapTime = "53.157", bestLapTime = "52.926"))

    val row = accumulator.apply(scoreboard(lap = 40, lapTime = "52.8", bestLapTime = "52.8")).single()

    assertEquals(listOf(40, 39), row.lapHistory.map(LapHistoryEntry::lap))
    assertEquals(listOf(52_800L, 53_157L), row.lapHistory.map(LapHistoryEntry::lapMs))
    assertEquals(40, row.bestLap)
    assertFalse(row.lapHistory.any { it.sector1Ms != null || it.sector2Ms != null || it.sector3Ms != null })
  }

  @Test
  fun newHeatClearsHistoryFromThePreviousSession() {
    val accumulator = ClubspeedTimingAccumulator()
    accumulator.apply(scoreboard(lap = 39, lapTime = "53.157", bestLapTime = "52.926"))

    val nextHeat =
      scoreboard(lap = 1, lapTime = "55.001", bestLapTime = "55.001", heat = "83371")
    val row = accumulator.apply(nextHeat).single()

    assertEquals(listOf(1), row.lapHistory.map(LapHistoryEntry::lap))
  }

  @Test
  fun parsesEveryTimeShapeReturnedByClubspeed() {
    assertEquals(61_420L, parseClubspeedTimeMs("61.42"))
    assertEquals(64_691L, parseClubspeedTimeMs("00:01:04.691"))
    assertEquals(125_250L, parseClubspeedTimeMs("2:05.250"))
    assertNull(parseClubspeedTimeMs("-"))
  }

  @Test
  fun mapsClubspeedGapToLeaderAtTheCurrentLap() {
    val row =
      ClubspeedTimingAccumulator().apply(
        scoreboard(
          lap = 12,
          lapTime = "53.157",
          bestLapTime = "52.926",
          position = 4,
          gapToLeader = "00:00:03.200",
        ),
      ).single()

    assertEquals(3_200L, row.gapToLeaderMs)
    assertEquals(12, row.gapRecordedAtLap)
  }

  private fun scoreboard(
    lap: Int,
    lapTime: String,
    bestLapTime: String,
    heat: String = "83370",
    position: Int = 1,
    gapToLeader: String = "-",
  ) =
    json.parseToJsonElement(
      """
      {
        "Winby":"By Position",
        "LapsLeft":"04:11 Left",
        "HeatTypeName":"SODI D40 Race",
        "ScoreboardData":[{
          "CustID":"1293214",
          "HeatNo":"$heat",
          "RacerName":"Anonymous",
          "AutoNo":"1",
          "LTime":"$lapTime",
          "LapNum":"$lap",
          "BestLTime":"$bestLapTime",
          "Position":"$position",
          "GapToLeader":"$gapToLeader"
        }],
        "RaceRunning":true
      }
      """.trimIndent(),
    ).jsonObject
}
