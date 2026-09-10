package com.example.lapbot.service

import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.OpportunityConfidence
import com.example.lapbot.data.RelativeOpportunityStatus
import com.example.lapbot.data.RelativeOpportunityUiState
import com.example.lapbot.data.TimingRow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class ExternalCoachingResult(
  val observation: CoachingObservation? = null,
  val ui: RelativeOpportunityUiState = RelativeOpportunityUiState(),
)

private data class ActiveExternalOpportunity(
  val comparison: RelativeSectorComparison,
  val cohortKartNumbers: List<String>,
  val confidence: OpportunityConfidence,
  val establishedAtLap: Int,
  var lastReminderAtLap: Int,
  val postAdviceSamples: MutableList<PaceSample> = mutableListOf(),
  var progressAnnounced: Boolean = false,
  var status: RelativeOpportunityStatus = RelativeOpportunityStatus.Working,
  var lastProgressSampleLap: Int = establishedAtLap,
  var currentOpportunityMs: Long = comparison.opportunityMs,
)

/** Session-scoped hysteresis, progress memory, and speech cadence for external opportunities. */
internal class ExternalOpportunityCoach(
  private val analyzer: RelativeOpportunityAnalyzer = RelativeOpportunityAnalyzer(),
) {
  private var acceptedCohortKey: List<String> = emptyList()
  private var pendingCohortKey: List<String>? = null
  private var pendingCohortCount = 0
  private var pendingSector: Int? = null
  private var pendingSectorCount = 0
  private var active: ActiveExternalOpportunity? = null
  private var missingOpportunityCount = 0
  private var lastProcessedLap: Int? = null
  private var reminderVariant = 0
  private var focusedDriverId: String? = null

  fun ui(): RelativeOpportunityUiState = active.toUiOrEmpty()

  fun reset() {
    focusedDriverId = null
    resetAnalysisState()
  }

  private fun resetAnalysisState() {
    acceptedCohortKey = emptyList()
    pendingCohortKey = null
    pendingCohortCount = 0
    pendingSector = null
    pendingSectorCount = 0
    active = null
    missingOpportunityCount = 0
    lastProcessedLap = null
    reminderVariant = 0
  }

  fun onLap(
    focused: TimingRow,
    field: List<TimingRow>,
    lap: Int,
    chattiness: CoachingChattiness,
  ): ExternalCoachingResult {
    if (focusedDriverId != focused.id) {
      focusedDriverId = focused.id
      resetAnalysisState()
    }
    if (lastProcessedLap == lap) return ExternalCoachingResult(ui = active.toUiOrEmpty())
    lastProcessedLap = lap
    val analysis = analyzer.analyze(focused, field)
    val desiredCohortKey = analysis.cohort.map(BenchmarkMember::driverId).sorted()
    if (!acceptCohort(desiredCohortKey)) return ExternalCoachingResult(ui = active.toUiOrEmpty(analysis.frontRunningSectors))

    val current = active
    if (current != null) {
      progressObservation(focused, lap, current)?.let {
        return ExternalCoachingResult(it, current.toUi(analysis.frontRunningSectors))
      }
    }

    val candidate = analysis.primary
    if (candidate == null) {
      pendingSector = null
      pendingSectorCount = 0
      if (current != null) {
        missingOpportunityCount += 1
        if (missingOpportunityCount >= 2) {
          current.status = RelativeOpportunityStatus.Resolved
          active = null
          return ExternalCoachingResult(
            CoachingObservation("Good work. The previous relative opportunity is no longer standing out", priority = 6),
            RelativeOpportunityUiState(status = RelativeOpportunityStatus.Resolved, frontRunningSectors = analysis.frontRunningSectors),
          )
        }
      }
      return ExternalCoachingResult(ui = current.toUiOrEmpty(analysis.frontRunningSectors))
    }
    missingOpportunityCount = 0

    if (current == null) {
      if (!confirmSector(candidate.sector)) return ExternalCoachingResult(ui = candidate.toUi(analysis, RelativeOpportunityStatus.Identified))
      val next =
        ActiveExternalOpportunity(
          candidate,
          analysis.cohort.map(BenchmarkMember::kartNumber).filter(String::isNotBlank),
          requireNotNull(analysis.confidence),
          lap,
          lap,
        )
      active = next
      return ExternalCoachingResult(newOpportunityMessage(next, chattiness), next.toUi(analysis.frontRunningSectors))
    }

    val currentComparison = analysis.comparisons.firstOrNull { it.sector == current.comparison.sector }
    val materiallyBetterCandidate =
      candidate.sector != current.comparison.sector && currentComparison != null &&
        candidate.excessDeficit >= currentComparison.excessDeficit + 0.0075 &&
        candidate.opportunityMs >= currentComparison.opportunityMs + 150
    if (materiallyBetterCandidate) {
      if (confirmSector(candidate.sector)) {
        val next =
          ActiveExternalOpportunity(
            candidate,
            analysis.cohort.map(BenchmarkMember::kartNumber).filter(String::isNotBlank),
            requireNotNull(analysis.confidence),
            lap,
            lap,
          )
        active = next
        return ExternalCoachingResult(newOpportunityMessage(next, chattiness), next.toUi(analysis.frontRunningSectors))
      }
    } else {
      pendingSector = null
      pendingSectorCount = 0
    }

    val reminderEvery =
      when (chattiness) {
        CoachingChattiness.Low -> 8
        CoachingChattiness.Medium -> 6
        CoachingChattiness.High -> 4
      }
    if (lap - current.lastReminderAtLap >= reminderEvery) {
      current.lastReminderAtLap = lap
      return ExternalCoachingResult(reminderMessage(current, chattiness), current.toUi(analysis.frontRunningSectors))
    }
    return ExternalCoachingResult(ui = current.toUi(analysis.frontRunningSectors))
  }

  private fun acceptCohort(desired: List<String>): Boolean {
    if (acceptedCohortKey.isEmpty()) {
      acceptedCohortKey = desired
      return true
    }
    if (desired == acceptedCohortKey) {
      pendingCohortKey = null
      pendingCohortCount = 0
      return true
    }
    if (pendingCohortKey == desired) pendingCohortCount += 1
    else {
      pendingCohortKey = desired
      pendingCohortCount = 1
    }
    if (pendingCohortCount < 2) return false
    acceptedCohortKey = desired
    pendingCohortKey = null
    pendingCohortCount = 0
    active = null
    missingOpportunityCount = 0
    pendingSector = null
    pendingSectorCount = 0
    return true
  }

  private fun confirmSector(sector: Int): Boolean {
    if (pendingSector == sector) pendingSectorCount += 1
    else {
      pendingSector = sector
      pendingSectorCount = 1
    }
    return pendingSectorCount >= 2
  }

  private fun progressObservation(
    focused: TimingRow,
    lap: Int,
    opportunity: ActiveExternalOpportunity,
  ): CoachingObservation? {
    if (lap <= opportunity.establishedAtLap) return null
    val completed =
      focused.lapHistory.filter { it.lap > opportunity.lastProgressSampleLap }.maxByOrNull { it.lap }
        ?: return null
    val time = completed.sectorTime(opportunity.comparison.sector) ?: return null
    opportunity.lastProgressSampleLap = completed.lap
    opportunity.postAdviceSamples += PaceSample(completed.lap, time)
    val pace = estimateRepeatablePace(opportunity.postAdviceSamples, clusterWidthMs = 120) ?: return null
    val expected = opportunity.comparison.benchmarkPaceMs * (1.0 + opportunity.comparison.typicalDeficit)
    val currentOpportunity = (pace.timeMs - expected).roundToLong().coerceAtLeast(0)
    opportunity.currentOpportunityMs = currentOpportunity
    val improvement = opportunity.comparison.opportunityMs - currentOpportunity
    val threshold = max(100L, (opportunity.comparison.opportunityMs * 0.25).roundToLong())
    if (improvement < threshold || opportunity.progressAnnounced) return null
    opportunity.progressAnnounced = true
    opportunity.status = RelativeOpportunityStatus.Improving
    return CoachingObservation(
      "Good progress in sector ${spokenSector(opportunity.comparison.sector)}. The additional loss has reduced by about ${formatSpokenDeltaMagnitude(improvement)}",
      opportunity.comparison.sector,
      priority = 7,
    )
  }

  private fun newOpportunityMessage(
    opportunity: ActiveExternalOpportunity,
    chattiness: CoachingChattiness,
  ): CoachingObservation {
    val comparison = opportunity.comparison
    val text =
      when (chattiness) {
        CoachingChattiness.Low -> "Sector ${spokenSector(comparison.sector)} looks like your clearest relative opportunity"
        CoachingChattiness.Medium ->
          "Sector ${spokenSector(comparison.sector)} looks like your clearest relative opportunity, with about ${formatSpokenDeltaMagnitude(comparison.opportunityMs)} of additional loss versus your normal gap"
        CoachingChattiness.High ->
          "Sector ${spokenSector(comparison.sector)} looks like your clearest relative opportunity. You're about ${formatPercent(comparison.relativeDeficit)} off the front-running repeatable pace there, versus roughly ${formatPercent(comparison.typicalDeficit)} generally"
      }
    return CoachingObservation(text, comparison.sector, priority = 6)
  }

  private fun reminderMessage(
    opportunity: ActiveExternalOpportunity,
    chattiness: CoachingChattiness,
  ): CoachingObservation {
    val sector = spokenSector(opportunity.comparison.sector)
    val variants =
      when (chattiness) {
        CoachingChattiness.Low -> listOf("Sector $sector remains the main relative opportunity")
        CoachingChattiness.Medium ->
          listOf(
            "Sector $sector remains the main relative opportunity. Keep working that area",
            "The relative opportunity is still sector $sector. Stay focused there",
          )
        CoachingChattiness.High ->
          listOf(
            "Sector $sector remains disproportionately off the front-running reference",
            "Faster drivers are still demonstrating more in sector $sector. Keep building",
            "Sector $sector remains the clearest additional loss relative to your normal gap",
          )
      }
    val text = variants[reminderVariant % variants.size]
    reminderVariant += 1
    return CoachingObservation(text, opportunity.comparison.sector, priority = 2)
  }
}

private fun ActiveExternalOpportunity?.toUiOrEmpty(frontRunningSectors: Set<Int> = emptySet()): RelativeOpportunityUiState =
  this?.toUi(frontRunningSectors) ?: RelativeOpportunityUiState(frontRunningSectors = frontRunningSectors)

private fun ActiveExternalOpportunity.toUi(frontRunningSectors: Set<Int>): RelativeOpportunityUiState =
  comparison.toUi(
    cohortKartNumbers,
    confidence,
    status,
    frontRunningSectors,
  ).copy(
    initialOpportunityMs = comparison.opportunityMs,
    opportunityMs = currentOpportunityMs,
  )

private fun RelativeSectorComparison.toUi(
  analysis: RelativeOpportunityAnalysis,
  status: RelativeOpportunityStatus,
): RelativeOpportunityUiState =
  toUi(
    analysis.cohort.map(BenchmarkMember::kartNumber).filter(String::isNotBlank),
    requireNotNull(analysis.confidence),
    status,
    analysis.frontRunningSectors,
  )

private fun RelativeSectorComparison.toUi(
  kartNumbers: List<String>,
  confidence: OpportunityConfidence,
  status: RelativeOpportunityStatus,
  frontRunningSectors: Set<Int>,
): RelativeOpportunityUiState =
  RelativeOpportunityUiState(
    sector = sector,
    benchmarkKartNumbers = kartNumbers,
    driverPaceMs = driverPace.timeMs,
    benchmarkPaceMs = benchmarkPaceMs,
    relativeDeficitPercent = relativeDeficit * 100.0,
    typicalDeficitPercent = typicalDeficit * 100.0,
    excessDeficitPercentagePoints = excessDeficit * 100.0,
    initialOpportunityMs = opportunityMs,
    opportunityMs = opportunityMs,
    driverSampleCount = driverPace.sampleCount,
    benchmarkSampleCount = benchmarkSampleCount,
    confidence = confidence,
    status = status,
    frontRunningSectors = frontRunningSectors,
  )

private fun LapHistoryEntry.sectorTime(sector: Int): Long? =
  when (sector) {
    1 -> sector1Ms
    2 -> sector2Ms
    else -> sector3Ms
  }

private fun formatPercent(fraction: Double): String {
  val percent = fraction * 100.0
  val rounded = (percent * 10).roundToInt() / 10.0
  return if (rounded % 1.0 == 0.0) "${rounded.roundToInt()} percent" else "$rounded percent"
}

private fun spokenSector(sector: Int): String = when (sector) { 1 -> "one"; 2 -> "two"; else -> "three" }
