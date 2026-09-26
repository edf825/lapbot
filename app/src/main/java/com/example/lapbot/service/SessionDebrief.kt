package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.RecordedEngineerEvent
import com.example.lapbot.data.RecordedLap
import com.example.lapbot.data.RecordedSession
import com.example.lapbot.data.TimingRow
import com.example.lapbot.data.toTimingRow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

internal enum class DebriefTrend { Improved, Stable, Slower, InsufficientData }

internal data class SessionDebrief(
  val bestLapMs: Long?,
  val repeatablePaceMs: Long?,
  val consistencyMs: Long?,
  val theoreticalLapMs: Long?,
  val theoreticalGapMs: Long?,
  val relativePacePercent: Double?,
  val benchmarkKartNumbers: List<String>,
  val strongestOpportunitySector: Int?,
  val opportunityMs: Long?,
  val opportunityImproved: Boolean?,
  val paceTrend: DebriefTrend,
  val consistencyTrend: DebriefTrend,
  val objectiveSummary: String,
  val conclusions: List<String>,
  val personalBestLaps: Set<Int>,
  val outlierLaps: Set<Int>,
)

internal class SessionDebriefAnalyzer {
  fun analyze(session: RecordedSession): SessionDebrief {
    val laps = session.laps.filter { it.lapTimeMs > 0 }.sortedBy(RecordedLap::lap)
    if (laps.isEmpty()) return emptyDebrief()
    val outliers = identifyOutliers(laps)
    val representative = laps.filterNot { it.lap in outliers }.ifEmpty { laps }
    val lapPace =
      estimateRepeatablePace(
        representative.map { PaceSample(it.lap, it.lapTimeMs) },
        clusterWidthMs = LAP_REPEATABLE_BAND_MS,
      )
    val repeatable = lapPace?.timeMs
    val consistency = representative.map(RecordedLap::lapTimeMs).takeIf { it.size >= 3 }?.let(::medianAbsoluteDeviation)
    val theoretical = theoreticalLap(representative)
    val best = representative.minOfOrNull(RecordedLap::lapTimeMs)

    val focused = focusedTimingRow(session, representative)
    val field =
      session.field.map { it.toTimingRow() }.map { if (it.id == focused.id) focused else it }
        .let { rows -> if (rows.none { it.id == focused.id }) rows + focused else rows }
    val relative = RelativeOpportunityAnalyzer().analyze(focused, field)
    val typicalDeficit = relative.comparisons.firstOrNull()?.typicalDeficit?.times(100.0)
    val primary = relative.primary
    val recordedRelative = latestRelativeOpportunity(session.engineerEvents)
    val opportunitySector = primary?.sector ?: recordedRelative?.sector
    val opportunityMs = primary?.opportunityMs ?: recordedRelative?.currentOpportunityMs ?: recordedRelative?.initialOpportunityMs
    val opportunityImproved = opportunityProgress(session.engineerEvents)
    val paceTrend = comparePaceWindows(representative)
    val consistencyTrend = compareConsistencyWindows(representative)
    val objectiveSummary = objectiveAssessment(session.engineerEvents)
    val conclusions =
      buildList {
        if (opportunitySector != null && opportunityMs != null) {
          add(
            when (opportunityImproved) {
              true -> "Sector $opportunitySector was the clearest credible opportunity and improved during the session."
              false -> "Sector $opportunitySector remained the clearest credible opportunity."
              null -> "Sector $opportunitySector was the clearest credible opportunity; there is not enough evidence to judge progress."
            },
          )
        } else {
          add("Not enough reliable sector evidence to identify a relative opportunity.")
        }
        add(
          when (paceTrend) {
            DebriefTrend.Improved -> "Representative pace improved late in the session."
            DebriefTrend.Slower -> "Representative pace was slower late in the session."
            DebriefTrend.Stable -> "Representative pace remained stable through the session."
            DebriefTrend.InsufficientData -> "Not enough representative laps to establish a pace trend."
          },
        )
        add(
          when (consistencyTrend) {
            DebriefTrend.Improved -> "Lap-to-lap consistency improved in the later laps."
            DebriefTrend.Slower -> "Lap-to-lap variation increased in the later laps."
            DebriefTrend.Stable -> "Lap-to-lap consistency remained broadly stable."
            DebriefTrend.InsufficientData -> "Not enough representative laps to establish a consistency trend."
          },
        )
        positionOrGapTrend(representative)?.let(::add)
      }
    return SessionDebrief(
      bestLapMs = best,
      repeatablePaceMs = repeatable,
      consistencyMs = consistency,
      theoreticalLapMs = theoretical,
      theoreticalGapMs = if (best != null && theoretical != null) (best - theoretical).coerceAtLeast(0) else null,
      relativePacePercent = typicalDeficit,
      benchmarkKartNumbers = relative.cohort.map { it.kartNumber }.filter(String::isNotBlank),
      strongestOpportunitySector = opportunitySector,
      opportunityMs = opportunityMs,
      opportunityImproved = opportunityImproved,
      paceTrend = paceTrend,
      consistencyTrend = consistencyTrend,
      objectiveSummary = objectiveSummary,
      conclusions = conclusions,
      personalBestLaps = runningPersonalBests(laps),
      outlierLaps = outliers,
    )
  }

  private fun focusedTimingRow(session: RecordedSession, laps: List<RecordedLap>): TimingRow =
    TimingRow(
      id = session.selectedDriverId,
      number = session.kartNumbers.lastOrNull().orEmpty(),
      name = session.selectedDriverName,
      position = laps.lastOrNull()?.position,
      lap = laps.lastOrNull()?.lap,
      lapMs = laps.lastOrNull()?.lapTimeMs,
      lapHistory = laps.map { LapHistoryEntry(it.lap, it.lapTimeMs, it.sector1Ms, it.sector2Ms, it.sector3Ms) },
    )

  private fun identifyOutliers(laps: List<RecordedLap>): Set<Int> {
    if (laps.size < 4) return emptySet()
    val times = laps.map(RecordedLap::lapTimeMs)
    val centre = median(times)
    val deviation = medianAbsoluteDeviation(times)
    val threshold = max(MINIMUM_OUTLIER_GAP_MS, deviation * OUTLIER_MAD_MULTIPLIER)
    return laps.filter { it.lapTimeMs > centre + threshold }.mapTo(mutableSetOf(), RecordedLap::lap)
  }

  private fun theoreticalLap(laps: List<RecordedLap>): Long? {
    val coherent = laps.filter(::hasCoherentSectors)
    if (coherent.size < 3) return null
    return listOf(
      coherent.mapNotNull(RecordedLap::sector1Ms).minOrNull(),
      coherent.mapNotNull(RecordedLap::sector2Ms).minOrNull(),
      coherent.mapNotNull(RecordedLap::sector3Ms).minOrNull(),
    ).takeIf { it.all { sector -> sector != null } }?.sumOf { requireNotNull(it) }
  }

  private fun hasCoherentSectors(lap: RecordedLap): Boolean {
    val sectors = listOf(lap.sector1Ms, lap.sector2Ms, lap.sector3Ms)
    if (sectors.any { it == null || it <= 0 }) return false
    val total = sectors.sumOf { requireNotNull(it) }
    val tolerance = max(50L, (lap.lapTimeMs * 0.001).roundToLong())
    return abs(total - lap.lapTimeMs) <= tolerance
  }

  private fun comparePaceWindows(laps: List<RecordedLap>): DebriefTrend {
    if (laps.size < 6) return DebriefTrend.InsufficientData
    val first = median(laps.take(3).map(RecordedLap::lapTimeMs))
    val last = median(laps.takeLast(3).map(RecordedLap::lapTimeMs))
    val delta = last - first
    return when {
      delta <= -MEANINGFUL_TREND_MS -> DebriefTrend.Improved
      delta >= MEANINGFUL_TREND_MS -> DebriefTrend.Slower
      else -> DebriefTrend.Stable
    }
  }

  private fun compareConsistencyWindows(laps: List<RecordedLap>): DebriefTrend {
    if (laps.size < 6) return DebriefTrend.InsufficientData
    val split = laps.size / 2
    val first = medianAbsoluteDeviation(laps.take(split).map(RecordedLap::lapTimeMs))
    val last = medianAbsoluteDeviation(laps.takeLast(split).map(RecordedLap::lapTimeMs))
    val meaningful = max(MINIMUM_CONSISTENCY_CHANGE_MS, (first * 0.25).roundToLong())
    return when {
      first - last >= meaningful -> DebriefTrend.Improved
      last - first >= meaningful -> DebriefTrend.Slower
      else -> DebriefTrend.Stable
    }
  }

  private fun objectiveAssessment(events: List<RecordedEngineerEvent>): String {
    val objective = events.lastOrNull { it.kind == "objective" }
      ?: return "No Race Engineer objective had enough evidence to be established."
    return when (objective.status) {
      "ImprovementConfirmed", "ReadyToReassess" -> "The evidence suggests the Race Engineer objective improved."
      "PromisingImprovement" -> "There was a promising attempt against the objective, but not enough repetition to confirm it."
      else -> "The Race Engineer objective was established, but sustained improvement was not confirmed."
    }
  }

  private fun opportunityProgress(events: List<RecordedEngineerEvent>): Boolean? {
    val relative = events.filter { it.kind == "relative_opportunity" && it.initialOpportunityMs != null }
    if (relative.isEmpty()) return null
    if (relative.any { it.status == "Improving" || it.status == "Resolved" }) return true
    val first = relative.first().initialOpportunityMs ?: return null
    val last = relative.last().currentOpportunityMs ?: return null
    return when {
      first - last >= max(100L, (first * 0.25).roundToLong()) -> true
      relative.size >= 2 -> false
      else -> null
    }
  }

  private fun latestRelativeOpportunity(events: List<RecordedEngineerEvent>): RecordedEngineerEvent? =
    events.lastOrNull { it.kind == "relative_opportunity" && it.sector != null }

  private fun positionOrGapTrend(laps: List<RecordedLap>): String? {
    val positioned = laps.filter { it.position != null }
    if (positioned.size >= 2) {
      val start = requireNotNull(positioned.first().position)
      val end = requireNotNull(positioned.last().position)
      if (end < start) return "Position improved from P$start to P$end."
      if (end > start) return "Final observed position moved from P$start to P$end."
    }
    val ahead = laps.mapNotNull(RecordedLap::gapAheadMs)
    if (ahead.size >= 4) {
      val change = median(ahead.takeLast(2)) - median(ahead.take(2))
      if (change <= -300) return "The reliable gap to the kart ahead reduced during the session."
      if (change >= 300) return "The reliable gap to the kart ahead increased during the session."
    }
    return null
  }

  private fun runningPersonalBests(laps: List<RecordedLap>): Set<Int> {
    var best = Long.MAX_VALUE
    return laps.filter {
      if (it.lapTimeMs < best) {
        best = it.lapTimeMs
        true
      } else false
    }.mapTo(mutableSetOf(), RecordedLap::lap)
  }

  private fun emptyDebrief() =
    SessionDebrief(
      null, null, null, null, null, null, emptyList(), null, null, null,
      DebriefTrend.InsufficientData, DebriefTrend.InsufficientData,
      "No meaningful laps were recorded.", listOf("Not enough reliable evidence."), emptySet(), emptySet(),
    )

  private companion object {
    const val LAP_REPEATABLE_BAND_MS = 360L
    const val MINIMUM_OUTLIER_GAP_MS = 1_500L
    const val OUTLIER_MAD_MULTIPLIER = 4L
    const val MEANINGFUL_TREND_MS = 150L
    const val MINIMUM_CONSISTENCY_CHANGE_MS = 75L
  }
}
