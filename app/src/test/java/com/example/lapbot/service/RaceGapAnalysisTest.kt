package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.TimingRow
import com.example.lapbot.data.TimingTracks
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class RaceGapAnalysisTest {
  @Test
  fun `gap announcements are supported by every configured track`() {
    assertEquals(emptyList<String>(), TimingTracks.All.filterNot { it.supportsGaps }.map { it.id })
  }

  @Test
  fun `cumulative provider gaps produce adjacent gaps`() {
    val rows =
      listOf(
        row("3", 3, gapToLeaderMs = 2_000),
        row("4", 4, gapToLeaderMs = 3_200),
        row("5", 5, gapToLeaderMs = 3_530),
      )

    val gaps = calculateAdjacentRaceGaps(rows, rows[1], completedLap = 6)

    assertEquals(PositionGap(3, "3", 1_200), gaps?.ahead)
    assertEquals(PositionGap(5, "5", 330), gaps?.behind)
    assertEquals(true, gaps?.isComplete)
    assertEquals(listOf("Gap to P3, 1 point 20", "Gap to P5, point 33"), formatGapAnnouncementSections(gaps))
  }

  @Test
  fun `kart numbers can be included without changing the gap calculation`() {
    val gaps =
      AdjacentRaceGaps(
        ahead = PositionGap(position = 3, kartNumber = "12", milliseconds = 1_200),
        behind = PositionGap(position = 5, kartNumber = "27", milliseconds = 330),
      )

    assertEquals(
      listOf("Gap to P3, kart 12, 1 point 20", "Gap to P5, kart 27, point 33"),
      formatGapAnnouncementSections(gaps, includeKartNumbers = true),
    )
    assertEquals(
      listOf("Gap to P3, 1 point 20", "Gap to P5, point 33"),
      formatGapAnnouncementSections(gaps, includeKartNumbers = false),
    )
  }

  @Test
  fun `gap calls fit inside the existing lap announcement budget`() {
    assertEquals(
      listOf(
        "52 point 34",
        "Point 40 off your best",
        "Gap to P3, 1 point 20",
        "Gap to P5, point 33",
        "Keep it tidy",
      ),
      formatBudgetedLapAnnouncementSections(
        lapTimeMs = 52_340,
        lastDeltaMs = -200,
        bestDeltaMs = 400,
        coaching = "Keep it tidy",
        gapSections = listOf("Gap to P3, 1 point 20", "Gap to P5, point 33"),
      ),
    )
  }

  @Test
  fun `provider interval takes priority over cumulative gap`() {
    val ahead = row("3", 3, gapToLeaderMs = 2_000)
    val focus = row("4", 4, gapToLeaderMs = 3_200, gapToAheadMs = 900)

    assertEquals(
      PositionGap(3, "3", 900),
      calculateAdjacentRaceGaps(listOf(ahead, focus), focus, completedLap = 6)?.ahead,
    )
  }

  @Test
  fun `mismatched lap measurements are suppressed`() {
    val ahead = row("3", 3, gapToLeaderMs = 2_000)
    val focus = row("4", 4, gapToLeaderMs = 3_200).copy(gapRecordedAtLap = 5)

    assertNull(calculateAdjacentRaceGaps(listOf(ahead, focus), focus, completedLap = 6))
  }

  @Test
  fun `complete lap histories provide the Buckmore fallback`() {
    val ahead = historyRow("3", 3, listOf(50_000, 50_000, 50_000))
    val focus = historyRow("4", 4, listOf(50_400, 50_300, 50_500))
    val behind = historyRow("5", 5, listOf(50_500, 50_500, 50_530))

    val gaps = calculateAdjacentRaceGaps(listOf(ahead, focus, behind), focus, completedLap = 3)

    assertEquals(PositionGap(3, "3", 1_200), gaps?.ahead)
    assertEquals(PositionGap(5, "5", 330), gaps?.behind)
  }

  @Test
  fun `incomplete fallback history does not invent a gap`() {
    val ahead = historyRow("3", 3, listOf(50_000, 50_000, 50_000))
    val focus = historyRow("4", 4, listOf(50_400, 50_300, 50_500)).copy(
      lapHistory = listOf(LapHistoryEntry(3, 50_500)),
    )

    assertNull(calculateAdjacentRaceGaps(listOf(ahead, focus), focus, completedLap = 3))
  }

  private fun row(
    id: String,
    position: Int,
    gapToLeaderMs: Long,
    gapToAheadMs: Long? = null,
  ) = TimingRow(
    id = id,
    number = id,
    position = position,
    lap = 6,
    gapToLeaderMs = gapToLeaderMs,
    gapToAheadMs = gapToAheadMs,
    gapRecordedAtLap = 6,
  )

  private fun historyRow(id: String, position: Int, times: List<Long>) =
    TimingRow(
      id = id,
      number = id,
      position = position,
      lap = times.size,
      lapHistory = times.mapIndexed { index, time -> LapHistoryEntry(index + 1, time) },
    )
}
