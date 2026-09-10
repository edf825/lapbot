package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.TimingRow
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class RelativeOpportunityAnalyzerTest {
  private val analyzer = RelativeOpportunityAnalyzer()

  @Test
  fun threeSixFourPercentDeficitsIdentifySectorTwoAndExcessTime() {
    val benchmark = row("benchmark", "1", 31_000, 20_000, 25_000)
    val focused = row("focused", "8", 31_930, 21_200, 26_000)

    val result = analyzer.analyze(focused, listOf(focused, benchmark))
    val opportunity = requireNotNull(result.primary)

    assertEquals(2, opportunity.sector)
    assertNear(0.06, opportunity.relativeDeficit)
    assertNear(0.04, opportunity.typicalDeficit)
    assertNear(0.02, opportunity.excessDeficit)
    assertEquals(400L, opportunity.opportunityMs)
  }

  @Test
  fun largestAbsoluteLossIsNotNecessarilyLargestRelativeOpportunity() {
    val benchmark = row("benchmark", "1", 50_000, 10_000, 20_000)
    val focused = row("focused", "8", 51_500, 10_600, 20_800)

    val result = analyzer.analyze(focused, listOf(focused, benchmark))

    assertEquals(2, result.primary?.sector)
    assertTrue(1_500L > 600L)
  }

  @Test
  fun largestRawPercentageIsSuppressedWhenNotDisproportionateEnough() {
    val benchmark = row("benchmark", "1", 30_000, 20_000, 25_000)
    val focused = row("focused", "8", 31_650, 21_200, 26_375)

    val result = analyzer.analyze(focused, listOf(focused, benchmark))

    assertNull(result.primary)
    assertNear(0.06, result.comparisons.single { it.sector == 2 }.relativeDeficit)
    assertNear(0.055, result.comparisons.single { it.sector == 2 }.typicalDeficit)
  }

  @Test
  fun oneOffBenchmarkPersonalBestDoesNotDistortRepeatablePace() {
    val samples =
      listOf(
        PaceSample(1, 19_000),
        PaceSample(2, 20_000),
        PaceSample(3, 20_040),
        PaceSample(4, 20_020),
        PaceSample(5, 20_030),
      )

    val pace = estimateRepeatablePace(samples, clusterWidthMs = 120)

    assertEquals(20_025L, pace?.timeMs)
  }

  @Test
  fun insufficientBenchmarkSamplesSuppressComparison() {
    val focused = row("focused", "8", 31_930, 21_200, 26_000)
    val benchmark = row("benchmark", "1", 31_000, 20_000, 25_000, samples = 2)

    assertNull(analyzer.analyze(focused, listOf(focused, benchmark)).primary)
  }

  @Test
  fun unavailableBenchmarkSectorDoesNotInvalidateOtherComparisons() {
    val focused = row("focused", "8", 31_930, 21_200, 26_000)
    val benchmark = row("benchmark", "1", 31_000, 20_000, null)

    val result = analyzer.analyze(focused, listOf(focused, benchmark))

    assertEquals(listOf(1, 2), result.comparisons.map { it.sector })
    assertNotNull(result.primary)
  }

  @Test
  fun fastestFocusedDriverHasNoExternalBenchmark() {
    val focused = row("focused", "8", 30_000, 20_000, 25_000)
    val slower = row("slower", "1", 31_000, 21_000, 26_000)

    assertTrue(analyzer.analyze(focused, listOf(focused, slower)).cohort.isEmpty())
  }

  @Test
  fun fastestOrCloseRepeatableSectorIsClassifiedAsFrontRunningPerformance() {
    val fastest = row("fastest", "1", 30_000, 20_000, 25_000)
    val focused = row("focused", "8", 30_120, 20_090, 25_200)

    val result = analyzer.analyze(focused, listOf(focused, fastest))

    assertEquals(setOf(1, 2), result.frontRunningSectors)
  }

  @Test
  fun cohortSectorBenchmarkUsesMedianOfCredibleFrontRunners() {
    val focused = row("focused", "8", 31_930, 21_200, 26_000)
    val first = row("first", "1", 31_000, 20_000, 25_000)
    val second = row("second", "2", 31_050, 20_040, 25_010)
    val third = row("third", "3", 31_020, 20_020, 25_020)

    val result = analyzer.analyze(focused, listOf(focused, first, second, third))

    assertEquals(3, result.cohort.size)
    assertEquals(20_020L, result.comparisons.single { it.sector == 2 }.benchmarkPaceMs)
  }

  private fun row(
    id: String,
    number: String,
    sector1: Long,
    sector2: Long,
    sector3: Long?,
    samples: Int = 5,
  ): TimingRow {
    val jitters = listOf(-20L, 0L, 20L, -10L, 10L).take(samples)
    return TimingRow(
      id = id,
      number = number,
      lapHistory =
        jitters.mapIndexed { index, jitter ->
          val s1 = sector1 + jitter
          val s2 = sector2 + jitter
          val s3 = sector3?.plus(jitter)
          LapHistoryEntry(index + 1, s1 + s2 + (s3 ?: 25_000L + jitter), s1, s2, s3)
        }.reversed(),
    )
  }

  private fun assertNear(expected: Double, actual: Double, tolerance: Double = 0.000_001) {
    assertTrue("Expected $expected, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
  }
}
