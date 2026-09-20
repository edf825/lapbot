package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.OpportunityConfidence
import com.example.lapbot.data.TimingRow
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class RelativeOpportunityPolicy(
  val analysisWindowLaps: Int = 10,
  val minimumSamples: Int = 3,
  val strongSampleCount: Int = 5,
  val sectorClusterWidthMs: Long = 120,
  val lapClusterWidthMs: Long = 360,
  val minimumFasterFraction: Double = 0.003,
  val cohortBandFraction: Double = 0.005,
  val minimumExcessDeficit: Double = 0.0075,
  val minimumOpportunityMs: Long = 150,
  val rankingTieFraction: Double = 0.0025,
)

internal data class PaceSample(val lap: Int, val timeMs: Long)

internal data class RepeatablePace(
  val timeMs: Long,
  val sampleCount: Int,
  val rangeMs: Long,
  val laps: List<Int>,
  val recentSupportCount: Int,
)

internal data class BenchmarkMember(
  val driverId: String,
  val kartNumber: String,
  val lapPace: RepeatablePace,
  val sectorPaces: Map<Int, RepeatablePace>,
)

internal data class RelativeSectorComparison(
  val sector: Int,
  val driverPace: RepeatablePace,
  val benchmarkPaceMs: Long,
  val benchmarkSampleCount: Int,
  val benchmarkDriverIds: List<String>,
  val relativeDeficit: Double,
  val typicalDeficit: Double,
  val excessDeficit: Double,
  val opportunityMs: Long,
)

internal data class RelativeOpportunityAnalysis(
  val cohort: List<BenchmarkMember> = emptyList(),
  val comparisons: List<RelativeSectorComparison> = emptyList(),
  val primary: RelativeSectorComparison? = null,
  val confidence: OpportunityConfidence? = null,
  val frontRunningSectors: Set<Int> = emptySet(),
)

internal fun estimateRepeatablePace(
  samples: List<PaceSample>,
  clusterWidthMs: Long,
  policy: RelativeOpportunityPolicy = RelativeOpportunityPolicy(),
): RepeatablePace? {
  val recentWindow = samples.sortedBy(PaceSample::lap).takeLast(policy.analysisWindowLaps)
  if (recentWindow.size < policy.minimumSamples) return null
  val sortedByTime = recentWindow.sortedBy(PaceSample::timeMs)
  val clusters =
    sortedByTime.indices.mapNotNull { start ->
      val end = sortedByTime.indexOfLast { it.timeMs - sortedByTime[start].timeMs <= clusterWidthMs }
      sortedByTime.subList(start, end + 1).takeIf { it.size >= policy.minimumSamples }
    }
  val cluster = clusters.minWithOrNull(compareBy<List<PaceSample>> { median(it.map(PaceSample::timeMs)) }.thenByDescending(List<PaceSample>::size))
    ?: return null
  val pace = median(cluster.map(PaceSample::timeMs))
  val recentSupport = recentWindow.takeLast(3).count { abs(it.timeMs - pace) <= clusterWidthMs }
  if (recentSupport < 2) return null
  return RepeatablePace(
    timeMs = pace,
    sampleCount = cluster.size,
    rangeMs = cluster.maxOf(PaceSample::timeMs) - cluster.minOf(PaceSample::timeMs),
    laps = cluster.map(PaceSample::lap).sorted(),
    recentSupportCount = recentSupport,
  )
}

internal class RelativeOpportunityAnalyzer(
  private val policy: RelativeOpportunityPolicy = RelativeOpportunityPolicy(),
) {
  fun analyze(focused: TimingRow, field: List<TimingRow>): RelativeOpportunityAnalysis {
    val focusedProfile = profile(focused) ?: return RelativeOpportunityAnalysis()
    val fieldProfiles = field.mapNotNull(::profile)
    val frontRunningSectors =
      (1..3).filter { sector ->
        val focusedPace = focusedProfile.sectorPaces[sector]?.timeMs ?: return@filter false
        val fastest = fieldProfiles.mapNotNull { it.sectorPaces[sector]?.timeMs }.minOrNull() ?: return@filter false
        focusedPace <= fastest * (1.0 + policy.cohortBandFraction)
      }.toSet()
    val eligible =
      fieldProfiles.asSequence()
        .filterNot { it.driverId == focused.id }
        .filter { it.lapPace.timeMs <= focusedProfile.lapPace.timeMs * (1.0 - policy.minimumFasterFraction) }
        .filter { it.sectorPaces.size >= 2 }
        .toList()
    val fastestLap =
      eligible.minOfOrNull { it.lapPace.timeMs }
        ?: return RelativeOpportunityAnalysis(frontRunningSectors = frontRunningSectors)
    val cohort =
      eligible.filter { it.lapPace.timeMs <= fastestLap * (1.0 + policy.cohortBandFraction) }
        .sortedWith(
          compareByDescending<BenchmarkMember> { it.sectorPaces.size }
            .thenByDescending { it.lapPace.sampleCount }
            .thenBy { it.lapPace.rangeMs }
            .thenBy { it.lapPace.timeMs }
            .thenBy { it.driverId },
        ).take(3)
    val provisional =
      (1..3).mapNotNull { sector ->
        val driverPace = focusedProfile.sectorPaces[sector] ?: return@mapNotNull null
        val contributors = cohort.mapNotNull { member -> member.sectorPaces[sector]?.let { member to it } }
        if (contributors.isEmpty()) return@mapNotNull null
        val benchmarkPace = median(contributors.map { it.second.timeMs })
        ProvisionalComparison(
          sector,
          driverPace,
          benchmarkPace,
          contributors.sumOf { it.second.sampleCount },
          contributors.map { it.first.driverId },
          (driverPace.timeMs - benchmarkPace).toDouble() / benchmarkPace,
        )
      }
    if (provisional.size < 2) return RelativeOpportunityAnalysis(cohort = cohort, frontRunningSectors = frontRunningSectors)
    val typicalDeficit = medianDouble(provisional.map(ProvisionalComparison::relativeDeficit))
    val comparisons =
      provisional.map { item ->
        val excess = item.relativeDeficit - typicalDeficit
        RelativeSectorComparison(
          sector = item.sector,
          driverPace = item.driverPace,
          benchmarkPaceMs = item.benchmarkPaceMs,
          benchmarkSampleCount = item.benchmarkSampleCount,
          benchmarkDriverIds = item.benchmarkDriverIds,
          relativeDeficit = item.relativeDeficit,
          typicalDeficit = typicalDeficit,
          excessDeficit = excess,
          opportunityMs = (item.benchmarkPaceMs * excess).roundToLong().coerceAtLeast(0),
        )
      }
    val eligibleOpportunities =
      comparisons.filter {
        it.excessDeficit >= policy.minimumExcessDeficit && it.opportunityMs >= policy.minimumOpportunityMs
      }
    val primary =
      eligibleOpportunities.maxWithOrNull { left, right ->
        val difference = left.excessDeficit - right.excessDeficit
        if (abs(difference) > policy.rankingTieFraction) difference.compareTo(0.0)
        else left.opportunityMs.compareTo(right.opportunityMs).takeIf { it != 0 } ?: right.sector.compareTo(left.sector)
      }
    val allThreeSectors = comparisons.size == 3
    val strong =
      allThreeSectors && focusedProfile.sectorPaces.values.all { it.sampleCount >= policy.strongSampleCount } &&
        cohort.size >= 2 && cohort.all { member -> member.sectorPaces.values.all { it.sampleCount >= policy.strongSampleCount } }
    return RelativeOpportunityAnalysis(
      cohort = cohort,
      comparisons = comparisons,
      primary = primary,
      confidence = if (strong) OpportunityConfidence.Strong else OpportunityConfidence.Established,
      frontRunningSectors = frontRunningSectors,
    )
  }

  private fun profile(driver: TimingRow): BenchmarkMember? {
    val validLaps = driver.lapHistory.filter(::isStructurallyValid).sortedBy(LapHistoryEntry::lap)
    val lapPace =
      estimateRepeatablePace(validLaps.map { PaceSample(it.lap, it.lapMs) }, policy.lapClusterWidthMs, policy)
        ?: return null
    val sectors =
      (1..3).mapNotNull { sector ->
        val samples = validLaps.mapNotNull { lap -> lap.sectorTime(sector)?.let { PaceSample(lap.lap, it) } }
        estimateRepeatablePace(samples, policy.sectorClusterWidthMs, policy)?.let { sector to it }
      }.toMap()
    return BenchmarkMember(driver.id, driver.number, lapPace, sectors)
  }

  private fun isStructurallyValid(lap: LapHistoryEntry): Boolean {
    val sectors = listOfNotNull(lap.sector1Ms, lap.sector2Ms, lap.sector3Ms)
    if (lap.lapMs <= 0 || sectors.any { it <= 0 }) return false
    if (sectors.size != 3) return true
    val tolerance = maxOf(50L, (lap.lapMs * 0.001).roundToLong())
    return abs(sectors.sum() - lap.lapMs) <= tolerance
  }
}

private data class ProvisionalComparison(
  val sector: Int,
  val driverPace: RepeatablePace,
  val benchmarkPaceMs: Long,
  val benchmarkSampleCount: Int,
  val benchmarkDriverIds: List<String>,
  val relativeDeficit: Double,
)

private fun LapHistoryEntry.sectorTime(sector: Int): Long? =
  when (sector) {
    1 -> sector1Ms
    2 -> sector2Ms
    else -> sector3Ms
  }

private fun medianDouble(values: List<Double>): Double {
  val sorted = values.sorted()
  val middle = sorted.size / 2
  return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}
