package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.TimingRow
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class SessionCoachingTest {
  private val establishedPolicy = EstablishedPacePolicy(minimumClusterSamples = 3, clusterBandWidthMs = 120)

  @Test
  fun establishedFastPace_ignoresOneOffPersonalBest() {
    val samples = listOf(17_100L, 17_450L, 17_460L, 17_440L, 17_470L)

    assertEquals(17_100L, samples.min())
    assertEquals(17_455L, establishedFastPace(samples, establishedPolicy))
  }

  @Test
  fun establishedFastPace_usesRepeatedFastBand() {
    val samples = listOf(17_100L, 17_140L, 17_120L, 17_180L, 17_160L)

    assertEquals(17_140L, establishedFastPace(samples, establishedPolicy))
  }

  @Test
  fun establishedFastPace_requiresARepeatedBand() {
    assertNull(establishedFastPace(listOf(17_100L, 17_300L, 17_550L), establishedPolicy))
  }

  @Test
  fun objectiveIsBasedOnRepeatedPaceNotOneOffPotential() {
    val coach = SessionCoach()
    val driver =
      TimingRow(
        id = "5",
        lapHistory =
          listOf(
            LapHistoryEntry(1, 53_000, 17_100, 17_450, 17_400),
            LapHistoryEntry(2, 53_100, 17_450, 17_470, 17_420),
            LapHistoryEntry(3, 53_200, 17_460, 17_460, 17_430),
            LapHistoryEntry(4, 53_100, 17_440, 17_480, 17_410),
            LapHistoryEntry(5, 53_000, 17_800, 17_490, 17_400),
            LapHistoryEntry(6, 53_000, 17_820, 17_500, 17_410),
            LapHistoryEntry(7, 53_000, 17_790, 17_490, 17_420),
            LapHistoryEntry(8, 53_000, 17_810, 17_490, 17_420),
          ),
      )

    val result = coach.onLap(driver, 8)

    assertEquals(1, result.objectiveUi.sector)
    assertTrue(requireNotNull(result.objectiveUi.opportunityMs) >= 300)
  }

  @Test
  fun budgetedLapCallKeepsOnlyOneComparisonWhenCoaching() {
    assertEquals(
      listOf("48 point 31", "Point 20 quicker than last", "Sector two is the next opportunity"),
      formatBudgetedLapAnnouncementSections(
        lapTimeMs = 48_310,
        lastDeltaMs = -200,
        bestDeltaMs = 100,
        coaching = "Sector two is the next opportunity",
      ),
    )
  }

  @Test
  fun highDetailRecognizesSectorsInsideRepeatableHighPerformanceWindow() {
    val coach = SessionCoach()
    val driver = consistentDriver()

    val first = coach.onSector(driver, lap = 5, sector = 1, timeMs = 17_080, chattiness = CoachingChattiness.High)
    val second = coach.onSector(driver, lap = 6, sector = 1, timeMs = 17_070, chattiness = CoachingChattiness.High)
    val firstText = requireNotNull(first.sectorObservation).text
    val secondText = requireNotNull(second.sectorObservation).text

    assertTrue(firstText.contains("within a tenth"))
    assertTrue(firstText.contains("sector one", ignoreCase = true))
    assertTrue(secondText.contains("within a tenth"))
    assertTrue(secondText.contains("consisten", ignoreCase = true))
  }

  @Test
  fun performanceWindowRecognitionIsExclusiveToHighDetail() {
    val driver = consistentDriver()

    assertNull(SessionCoach().onSector(driver, 5, 1, 17_080, CoachingChattiness.Low).sectorObservation)
    assertNull(SessionCoach().onSector(driver, 5, 1, 17_080, CoachingChattiness.Medium).sectorObservation)
  }

  @Test
  fun highDetailDoesNotPraiseSectorOutsideHighPerformanceWindow() {
    val result = SessionCoach().onSector(consistentDriver(), 5, 1, 17_200, CoachingChattiness.High)

    assertNull(result.sectorObservation)
  }

  @Test
  fun highDetailRecognizesFinalSectorFromCompletedLapWithoutUsingItAsBaselineEvidence() {
    val completedDriver =
      consistentDriver().copy(
        lapHistory = consistentDriver().lapHistory + LapHistoryEntry(5, 51_140, 17_040, 17_040, 17_060),
      )

    val result = SessionCoach().onSector(completedDriver, 5, 3, 17_060, CoachingChattiness.High)
    val message = requireNotNull(result.sectorObservation).text

    assertTrue(message.contains("within a tenth"))
    assertTrue(message.contains("sector three", ignoreCase = true))
  }

  @Test
  fun repeatedHighPerformanceRecognitionVariesWithoutLosingPrecision() {
    val coach = SessionCoach()
    val driver = consistentDriver()

    coach.onSector(driver, 5, 1, 17_080, CoachingChattiness.High)
    val messages =
      (6..9).map { lap ->
        requireNotNull(coach.onSector(driver, lap, 1, 17_070, CoachingChattiness.High).sectorObservation).text
      }

    assertEquals(4, messages.toSet().size)
    assertTrue(messages.all { it.contains("within a tenth") })
    assertTrue(messages.all { it.contains("sector one", ignoreCase = true) })
  }

  private fun consistentDriver() =
    TimingRow(
      id = "5",
      lapHistory =
        listOf(
          LapHistoryEntry(1, 51_100, 17_000, 17_000, 17_100),
          LapHistoryEntry(2, 51_120, 17_040, 17_010, 17_070),
          LapHistoryEntry(3, 51_080, 17_020, 17_020, 17_040),
          LapHistoryEntry(4, 51_100, 17_030, 17_030, 17_040),
        ),
    )
}
