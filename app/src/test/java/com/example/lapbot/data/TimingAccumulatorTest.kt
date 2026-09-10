package com.example.lapbot.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class TimingAccumulatorTest {
  @Test
  fun `empty refresh can preserve existing timing data`() {
    val accumulator = TimingAccumulator()
    accumulator.replace(
      json.parseToJsonElement(
        """{"Competitors":[{"CompetitorId":7,"CompetitorNumber":"12"}]}""",
      ).jsonObject,
    )

    val rows = accumulator.replace(JsonObject(emptyMap()), preserveIfEmpty = true)

    assertEquals(1, rows.size)
    assertEquals("12", rows.single().number)
  }

  private val json = Json

  @Test
  fun sparseLapPatchesAreMerged() {
    val accumulator = TimingAccumulator()
    accumulator.replace(
      json.parseToJsonElement(
        """{"Sequence":10,"Competitors":[{"CompetitorId":7,"CompetitorNumber":"12","CompetitorName":"Test Driver","Position":1,"Laps":[{"LapNumber":2,"Split1Time":1000}]}]}""",
      ).jsonObject,
    )

    val events =
      accumulator.apply(
        json.parseToJsonElement(
          """{"Sequence":11,"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":2,"Split2Time":2000,"Split3Time":3000,"LapTime":6000}]}]}""",
        ).jsonObject,
      )

    val row = accumulator.sortedRows().single()
    assertEquals(1000L, row.sector1Ms)
    assertEquals(2000L, row.sector2Ms)
    assertEquals(6000L, row.lapMs)
    assertEquals(2, row.recentCompletedLap)
    assertEquals(6000L, row.recentCompletedLapMs)
    assertEquals(2, row.bestLap)
    assertEquals(6000L, row.bestLapMs)
    assertEquals("Test Driver", row.name)
    assertTrue(events.single().toString().contains("\"complete\":true"))
  }

  @Test
  fun alphaBehindAndGapFieldsMapToLeaderAndPrecedingPosition() {
    val accumulator = TimingAccumulator()
    val rows =
      accumulator.replace(
        json.parseToJsonElement(
          """{"Competitors":[{"CompetitorId":1,"Position":1,"Laps":[{"LapNumber":2,"LapTime":43212,"Split1Time":14000,"Split2Time":15000,"Split3Time":14212}]},{"CompetitorId":2,"Position":2,"Behind":830,"Gap":830,"Laps":[{"LapNumber":2,"LapTime":44042,"Split1Time":14300,"Split2Time":15300,"Split3Time":14442}]},{"CompetitorId":3,"Position":3,"Behind":1059,"Gap":229,"Laps":[{"LapNumber":2,"LapTime":44271,"Split1Time":14400,"Split2Time":15400,"Split3Time":14471}]}]}""",
        ).jsonObject,
      )

    assertEquals(0L, rows[0].gapToLeaderMs)
    assertEquals(830L, rows[1].gapToLeaderMs)
    assertEquals(830L, rows[1].gapToAheadMs)
    assertEquals(1_059L, rows[2].gapToLeaderMs)
    assertEquals(229L, rows[2].gapToAheadMs)
    assertEquals(2, rows[2].gapRecordedAtLap)
  }

  @Test
  fun completedLapMetricsIgnorePartialCurrentLap() {
    val accumulator = TimingAccumulator()
    val rows =
      accumulator.replace(
        json.parseToJsonElement(
          """{"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":1,"LapTime":6200,"Split1Time":1000,"Split2Time":2100,"Split3Time":3100},{"LapNumber":2,"LapTime":6000,"Split1Time":1000,"Split2Time":2000,"Split3Time":3000},{"LapNumber":3,"Split1Time":1000}]}]}""",
        ).jsonObject,
      )

    val row = rows.single()
    assertEquals(3, row.lap)
    assertEquals(null, row.lapMs)
    assertEquals(2, row.recentCompletedLap)
    assertEquals(6000L, row.recentCompletedLapMs)
    assertEquals(2, row.bestLap)
    assertEquals(6000L, row.bestLapMs)
    assertEquals(1000L, row.bestSector1Ms)
    assertEquals(6000L, row.theoreticalBestMs)
    assertEquals(listOf(2, 1), row.lapHistory.map { it.lap })
    assertEquals(listOf(3, 2, 1), row.lapTimeline.map { it.lap })
    assertEquals(1000L, row.lapTimeline.first().sector1Ms)
    assertEquals(null, row.lapTimeline.first().lapMs)
  }

  @Test
  fun incompleteLapsAreExcludedFromMetricsAndHistory() {
    val row =
      TimingAccumulator()
        .replace(
          json.parseToJsonElement(
            """{"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":1,"LapTime":1000,"Split1Time":300},{"LapNumber":2,"LapTime":6000,"Split1Time":1000,"Split2Time":2000,"Split3Time":3000}]}]}""",
          ).jsonObject,
        ).single()

    assertEquals(2, row.bestLap)
    assertEquals(6000L, row.bestLapMs)
    assertEquals(listOf(2), row.lapHistory.map { it.lap })
    assertEquals(listOf(2, 1), row.lapTimeline.map { it.lap })
  }

  @Test
  fun sinceLapFallsBackToLatestUntilThresholdIsReached() {
    val row =
      TimingRow(
        id = "7",
        lapHistory =
          listOf(
            LapHistoryEntry(4, 6000, 1000, 2000, 3000),
            LapHistoryEntry(3, 6100, 1000, 2100, 3000),
          ),
      )

    assertEquals(listOf(4), row.metricLapsSince(5).map { it.lap })
    assertEquals(listOf(4), row.metricLapsSince(4).map { it.lap })
    assertEquals(listOf(4, 3), row.metricLapsSince(3).map { it.lap })
  }

  @Test
  fun theoreticalBestCombinesBestIndividualSectors() {
    val accumulator = TimingAccumulator()
    val rows =
      accumulator.replace(
        json.parseToJsonElement(
          """{"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":1,"LapTime":6000,"Split1Time":1000,"Split2Time":2100,"Split3Time":3000},{"LapNumber":2,"LapTime":6100,"Split1Time":1100,"Split2Time":2000,"Split3Time":2900}]}]}""",
        ).jsonObject,
      )

    val row = rows.single()
    assertEquals(1000L, row.bestSector1Ms)
    assertEquals(2000L, row.bestSector2Ms)
    assertEquals(2900L, row.bestSector3Ms)
    assertEquals(5900L, row.theoreticalBestMs)
    assertEquals(1000L, row.bestLapSector1Ms)
    assertEquals(2100L, row.bestLapSector2Ms)
    assertEquals(3000L, row.bestLapSector3Ms)
  }

  @Test
  fun newerLapClearsPreviousLapTimes() {
    val accumulator = TimingAccumulator()
    accumulator.replace(
      json.parseToJsonElement(
        """{"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":2,"Split1Time":1000,"LapTime":6000}]}]}""",
      ).jsonObject,
    )

    accumulator.apply(
      json.parseToJsonElement(
        """{"Competitors":[{"CompetitorId":7,"Laps":[{"LapNumber":3,"Split1Time":1100}]}]}""",
      ).jsonObject,
    )

    val row = accumulator.sortedRows().single()
    assertEquals(3, row.lap)
    assertEquals(1100L, row.sector1Ms)
    assertEquals(null, row.sector2Ms)
    assertEquals(null, row.lapMs)
  }
}
