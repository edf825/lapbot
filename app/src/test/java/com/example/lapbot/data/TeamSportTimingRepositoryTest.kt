package com.example.lapbot.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class TeamSportTimingRepositoryTest {
  private val json = Json

  @Test
  fun farnboroughUsesTheObservedSmsTimingSubscription() {
    assertEquals(
      "wss://webserver3.sms-timing.com:10015/",
      TeamSportTimingRepository.FARNBOROUGH_WEB_SOCKET_URL,
    )
    assertEquals(
      "START 260831@teamsportfarnborough",
      subscriptionCommand(TeamSportTimingRepository.FARNBOROUGH_RESOURCE_KEY),
    )
    assertEquals(TimingTracks.TeamSportFarnborough, TimingTracks.find("teamsport-farnborough"))
    assertFalse(TimingTracks.TeamSportFarnborough.supportsSectors)
  }

  @Test
  fun capturedActiveHeatMapsLapOnlyTiming() {
    val accumulator = TeamSportTimingAccumulator("260831@teamsportfarnborough")

    val row = accumulator.apply(snapshot(start = 1_788_806_700, lap = 2, lapMs = 51_809)).single()

    assertEquals("sms-timing:260831@teamsportfarnborough:87660667", row.id)
    assertEquals("sms-timing:260831@teamsportfarnborough:1788806700", accumulator.sessionKey)
    assertEquals("18", row.number)
    assertEquals("Test Driver", row.name)
    assertEquals(5, row.position)
    assertEquals(4_650L, row.gapToLeaderMs)
    assertEquals(2, row.gapRecordedAtLap)
    assertEquals(2, row.lap)
    assertEquals(51_809L, row.lapMs)
    assertEquals(51_809L, row.recentCompletedLapMs)
    assertEquals(51_809L, row.bestLapMs)
    assertEquals(listOf(LapHistoryEntry(2, 51_809)), row.lapHistory)
    assertNull(row.sector1Ms)
    assertNull(row.theoreticalBestMs)
  }

  @Test
  fun repeatedSnapshotsDoNotDuplicateLaps() {
    val accumulator = TeamSportTimingAccumulator("resource")
    val update = snapshot(start = 100, lap = 10, lapMs = 52_641, bestLapMs = 52_256)

    accumulator.apply(update)
    val row = accumulator.apply(update).single()

    assertEquals(listOf(10), row.lapHistory.map(LapHistoryEntry::lap))
    assertEquals(52_256L, row.bestLapMs)
    assertNull(row.bestLap)
  }

  @Test
  fun increasingLapCountBuildsObservedHistory() {
    val accumulator = TeamSportTimingAccumulator("resource")
    accumulator.apply(snapshot(start = 100, lap = 10, lapMs = 52_641, bestLapMs = 52_256))

    val row =
      accumulator.apply(snapshot(start = 100, lap = 11, lapMs = 52_100, bestLapMs = 52_100)).single()

    assertEquals(listOf(11, 10), row.lapHistory.map(LapHistoryEntry::lap))
    assertEquals(listOf(52_100L, 52_641L), row.lapHistory.map(LapHistoryEntry::lapMs))
    assertEquals(11, row.bestLap)
    assertFalse(row.lapHistory.any { it.sector1Ms != null || it.sector2Ms != null || it.sector3Ms != null })
  }

  @Test
  fun newHeatStartClearsObservedHistory() {
    val accumulator = TeamSportTimingAccumulator("resource")
    accumulator.apply(snapshot(start = 100, lap = 10, lapMs = 52_641))

    val row = accumulator.apply(snapshot(start = 101, lap = 1, lapMs = 55_001)).single()

    assertEquals("sms-timing:resource:101", accumulator.sessionKey)
    assertEquals(listOf(1), row.lapHistory.map(LapHistoryEntry::lap))
  }

  @Test
  fun emptyNoRaceSnapshotClearsRowsAndSession() {
    val accumulator = TeamSportTimingAccumulator("resource")
    accumulator.apply(snapshot(start = 100, lap = 10, lapMs = 52_641))

    val rows = accumulator.apply(json.parseToJsonElement("{}").jsonObject)

    assertEquals(emptyList<TimingRow>(), rows)
    assertNull(accumulator.sessionKey)
  }

  @Test
  fun smsTimingGapParserHandlesObservedWireValues() {
    assertEquals(4_930L, parseSmsTimingGapMs("+04.930"))
    assertEquals(330L, parseSmsTimingGapMs("00.330"))
    assertNull(parseSmsTimingGapMs(""))
  }

  private fun snapshot(
    start: Long,
    lap: Int,
    lapMs: Long,
    bestLapMs: Long = lapMs,
  ) =
    json.parseToJsonElement(
      """
      {
        "T": $start,
        "CE": 0,
        "CS": 1,
        "D": [{
          "LP": 0,
          "A": 65665,
          "B": $bestLapMs,
          "K": "18",
          "G": "04.650",
          "D": 87660667,
          "L": $lap,
          "T": $lapMs,
          "R": 5,
          "N": "Test Driver",
          "P": 5,
          "M": 0
        }],
        "EM": 0,
        "C": 768318,
        "N": "Test Heat",
        "E": 1,
        "R": 1,
        "L": 0,
        "S": 1
      }
      """.trimIndent(),
    ).jsonObject
}
