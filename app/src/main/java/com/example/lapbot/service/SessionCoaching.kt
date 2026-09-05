package com.example.lapbot.service

import com.example.lapbot.data.CoachingObjectiveStatus
import com.example.lapbot.data.CoachingObjectiveUiState
import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.TimingRow
import kotlin.math.abs
import kotlin.math.max

/** Source timing can be clean without being an established, repeatable pace. */
internal enum class CoachingSampleQuality {
  Representative,
  SuspectDataError,
  SuspectContextual,
  Compromised,
}

internal data class EstablishedPacePolicy(
  val minimumClusterSamples: Int = 3,
  val clusterBandWidthMs: Long = 120,
)

internal data class CoachingThresholdPolicy(
  val minimumMeaningfulDeltaMs: Long = 150,
  val resolvedOpportunityMs: Long = 100,
  val objectiveSwitchAbsoluteMs: Long = 150,
  val objectiveSwitchRatio: Double = 1.4,
  val noProgressLapCount: Int = 5,
  val recentWindowSize: Int = 5,
  val objectiveProgressSamples: Int = 4,
  val sectorCooldownAttempts: Int = 3,
  val lapCooldownLaps: Int = 3,
  val varianceMultiplier: Double = 0.5,
  val highPerformanceWindowMs: Long = 100,
  val establishedPace: EstablishedPacePolicy = EstablishedPacePolicy(),
) {
  fun effectiveDeltaMs(samples: List<Long>): Long =
    max(minimumMeaningfulDeltaMs, (medianAbsoluteDeviation(samples) * varianceMultiplier).toLong())
}

internal object TrackCoachingPolicies {
  val buckmorePark = CoachingThresholdPolicy()
}

/**
 * Finds the fastest *repeated* band, rather than choosing the N fastest attempts.
 * A single breakthrough can therefore improve demonstrated potential without changing
 * the pace used for coaching objectives.
 */
internal fun establishedFastPace(samples: List<Long>, policy: EstablishedPacePolicy): Long? {
  if (samples.size < policy.minimumClusterSamples) return null
  val sorted = samples.sorted()
  val clusters = mutableListOf<List<Long>>()
  sorted.indices.forEach { start ->
    val end = sorted.indexOfLast { it - sorted[start] <= policy.clusterBandWidthMs }
    if (end >= start + policy.minimumClusterSamples - 1) {
      clusters += sorted.subList(start, end + 1)
    }
  }
  // The lowest-median qualifying maximal band is the fastest repeatable pace.
  return clusters.minByOrNull { median(it) }?.let(::median)
}

internal fun median(values: List<Long>): Long {
  require(values.isNotEmpty())
  val sorted = values.sorted()
  val middle = sorted.size / 2
  return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

internal fun medianAbsoluteDeviation(values: List<Long>): Long {
  if (values.size < 3) return 0
  val centre = median(values)
  return median(values.map { abs(it - centre) })
}

internal data class Objective(
  val sector: Int,
  val establishedAtLap: Int,
  val recentMedianAtStartMs: Long,
  val repeatableFastAtStartMs: Long,
  val opportunityAtStartMs: Long,
  val status: CoachingObjectiveStatus,
)

internal data class CoachingObservation(
  val text: String,
  val sector: Int? = null,
  val priority: Int = 0,
)

internal data class CoachingResult(
  val sectorObservation: CoachingObservation? = null,
  val lapObservation: CoachingObservation? = null,
  val objectiveUi: CoachingObjectiveUiState = CoachingObjectiveUiState(),
)

private data class ObjectiveMemory(
  var objective: Objective,
  var workingBaselineMs: Long,
  val postAdviceAttempts: MutableList<Long> = mutableListOf(),
  val qualifyingRun: MutableList<Long> = mutableListOf(),
  var objectiveImprovementAnnounced: Boolean = false,
  var lastSectorFeedbackAtAttempt: Int = Int.MIN_VALUE,
  var lastSectorFeedbackLap: Int = Int.MIN_VALUE,
  var lastLapFeedbackAtLap: Int = Int.MIN_VALUE,
  var lastTheoreticalInsightAtLap: Int = Int.MIN_VALUE,
)

private data class RecognitionMemory(
  val consecutiveAttemptsBySector: MutableMap<Int, Int> = mutableMapOf(),
  val lastRecognizedLapBySector: MutableMap<Int, Int> = mutableMapOf(),
)

/** Per-selected-driver, active-session coaching memory. All calculations stay deterministic. */
internal class SessionCoach(private val policy: CoachingThresholdPolicy = TrackCoachingPolicies.buckmorePark) {
  private val objectives = mutableMapOf<String, ObjectiveMemory>()
  private val recognitions = mutableMapOf<String, RecognitionMemory>()
  private var phraseBank = CoachingPhraseBank()

  fun reset() {
    objectives.clear()
    recognitions.clear()
    phraseBank = CoachingPhraseBank()
  }

  fun objectiveUi(driverId: String): CoachingObjectiveUiState = objectives[driverId]?.toUi() ?: CoachingObjectiveUiState()

  fun onSector(
    driver: TimingRow,
    lap: Int,
    sector: Int,
    timeMs: Long,
    chattiness: CoachingChattiness = CoachingChattiness.Low,
  ): CoachingResult {
    val recognition = highPerformanceRecognition(driver, lap, sector, timeMs, chattiness)
    val memory =
      objectives[driver.id]
        ?: return CoachingResult(sectorObservation = recognition, objectiveUi = objectiveUi(driver.id))
    if (memory.objective.sector != sector || lap <= memory.objective.establishedAtLap) {
      return CoachingResult(sectorObservation = recognition, objectiveUi = memory.toUi())
    }
    memory.postAdviceAttempts += timeMs
    val samples = sectorSamples(driver, sector, includeCurrent = timeMs)
    val threshold = policy.effectiveDeltaMs(samples)
    val qualifies = timeMs <= memory.workingBaselineMs - threshold
    if (qualifies) memory.qualifyingRun += timeMs else memory.qualifyingRun.clear()
    val attempt = memory.postAdviceAttempts.size
    val observation =
      when (memory.qualifyingRun.size) {
        1 -> {
          memory.objective = memory.objective.copy(status = CoachingObjectiveStatus.PromisingImprovement)
          if (attempt - memory.lastSectorFeedbackAtAttempt >= sectorCooldown(chattiness)) {
            memory.lastSectorFeedbackAtAttempt = attempt
            memory.lastSectorFeedbackLap = lap
            CoachingObservation(
              "Great sector ${sectorName(sector)} — ${formatSpokenDeltaMagnitude(memory.workingBaselineMs - timeMs)} better",
              sector,
              priority = 3,
            )
          } else null
        }
        2 -> {
          memory.workingBaselineMs = median(memory.qualifyingRun)
          memory.qualifyingRun.clear()
          memory.objective = memory.objective.copy(status = CoachingObjectiveStatus.ImprovementConfirmed)
          // Confirmation is materially different from the first good attempt and must not
          // be hidden by the ordinary repeat-message cooldown.
          memory.lastSectorFeedbackAtAttempt = attempt
          memory.lastSectorFeedbackLap = lap
          CoachingObservation("Good. That sector ${sectorName(sector)} pace is becoming consistent", sector, priority = 5)
        }
        else -> null
      }
    return CoachingResult(sectorObservation = observation ?: recognition, objectiveUi = memory.toUi())
  }

  private fun highPerformanceRecognition(
    driver: TimingRow,
    lap: Int,
    sector: Int,
    timeMs: Long,
    chattiness: CoachingChattiness,
  ): CoachingObservation? {
    if (chattiness != CoachingChattiness.High) {
      recognitions[driver.id]?.consecutiveAttemptsBySector?.set(sector, 0)
      return null
    }
    val memory = recognitions.getOrPut(driver.id, ::RecognitionMemory)
    val historicalSamples = sectorSamples(driver, sector, beforeLap = lap)
    val repeatableFastPace = establishedFastPace(historicalSamples, policy.establishedPace)
    val inHighPerformanceWindow =
      repeatableFastPace != null && timeMs <= repeatableFastPace + policy.highPerformanceWindowMs
    if (!inHighPerformanceWindow) {
      memory.consecutiveAttemptsBySector[sector] = 0
      return null
    }
    if (memory.lastRecognizedLapBySector[sector] == lap) return null
    val consecutiveAttempts = (memory.consecutiveAttemptsBySector[sector] ?: 0) + 1
    memory.consecutiveAttemptsBySector[sector] = consecutiveAttempts
    memory.lastRecognizedLapBySector[sector] = lap
    val message = phraseBank.highPerformanceRecognition(driver.id, sector, consecutive = consecutiveAttempts >= 2)
    return CoachingObservation(message, sector, priority = 2)
  }

  fun onLap(
    driver: TimingRow,
    lap: Int,
    chattiness: CoachingChattiness = CoachingChattiness.Low,
  ): CoachingResult {
    val opportunities = opportunities(driver)
    val current = objectives[driver.id]
    if (current == null) {
      val next = opportunities.maxByOrNull { it.gapMs }
      if (next == null) return CoachingResult(objectiveUi = CoachingObjectiveUiState())
      val objective =
        Objective(
          sector = next.sector,
          establishedAtLap = lap,
          recentMedianAtStartMs = next.recentMedianMs,
          repeatableFastAtStartMs = next.establishedFastMs,
          opportunityAtStartMs = next.gapMs,
          status = CoachingObjectiveStatus.Working,
        )
      objectives[driver.id] = ObjectiveMemory(objective, next.recentMedianMs)
      return CoachingResult(
        lapObservation = CoachingObservation("Let's focus on sector ${sectorName(next.sector)}", next.sector, priority = 4),
        objectiveUi = objectives.getValue(driver.id).toUi(),
      )
    }
    val memory = current
    val objectiveGap = opportunities.firstOrNull { it.sector == memory.objective.sector }?.gapMs ?: 0
    val bestReplacement = opportunities.filter { it.sector != memory.objective.sector }.maxByOrNull { it.gapMs }
    val shouldReplace =
      objectiveGap <= policy.resolvedOpportunityMs ||
        (bestReplacement != null &&
          bestReplacement.gapMs >= objectiveGap + policy.objectiveSwitchAbsoluteMs &&
          bestReplacement.gapMs >= (objectiveGap * policy.objectiveSwitchRatio).toLong())
    if (shouldReplace && bestReplacement != null) {
      val replacement =
        Objective(
          bestReplacement.sector,
          lap,
          bestReplacement.recentMedianMs,
          bestReplacement.establishedFastMs,
          bestReplacement.gapMs,
          CoachingObjectiveStatus.Working,
        )
      objectives[driver.id] = ObjectiveMemory(replacement, bestReplacement.recentMedianMs)
      return CoachingResult(
        lapObservation = CoachingObservation("Sector ${sectorName(bestReplacement.sector)} is the next opportunity", bestReplacement.sector, 4),
        objectiveUi = objectives.getValue(driver.id).toUi(),
      )
    }
    val postMedian = memory.postAdviceAttempts.takeIf { it.size >= policy.objectiveProgressSamples }?.let(::median)
    val threshold = policy.effectiveDeltaMs(sectorSamples(driver, memory.objective.sector))
    if (
      postMedian != null &&
        !memory.objectiveImprovementAnnounced &&
        postMedian <= memory.objective.recentMedianAtStartMs - threshold &&
        lap - memory.lastLapFeedbackAtLap >= lapCooldown(chattiness)
    ) {
      memory.objectiveImprovementAnnounced = true
      memory.lastLapFeedbackAtLap = lap
      memory.objective = memory.objective.copy(status = CoachingObjectiveStatus.ReadyToReassess)
      return CoachingResult(
        lapObservation =
          CoachingObservation(
            "Sector ${sectorName(memory.objective.sector)} has improved by ${formatSpokenDeltaMagnitude(memory.objective.recentMedianAtStartMs - postMedian)} since we started working on it",
            memory.objective.sector,
            6,
          ),
        objectiveUi = memory.toUi(),
      )
    }
    if (memory.lastSectorFeedbackLap == lap) return CoachingResult(objectiveUi = memory.toUi())
    val observation =
      trendObservation(driver, lap, threshold, memory, chattiness)
        ?: sectorTrendObservation(driver, lap, memory, chattiness)
        ?: theoreticalInsight(driver, lap, threshold, memory, chattiness)
    return CoachingResult(lapObservation = observation, objectiveUi = memory.toUi())
  }

  private fun trendObservation(
    driver: TimingRow,
    lap: Int,
    threshold: Long,
    memory: ObjectiveMemory,
    chattiness: CoachingChattiness,
  ): CoachingObservation? {
    if (lap - memory.lastLapFeedbackAtLap < lapCooldown(chattiness)) return null
    val values = driver.lapHistory.sortedBy { it.lap }.map { it.lapMs }
    if (values.size < policy.establishedPace.minimumClusterSamples * 2) return null
    val recent = values.takeLast(policy.recentWindowSize)
    val preceding = values.dropLast(recent.size).takeLast(policy.recentWindowSize)
    if (preceding.size < policy.establishedPace.minimumClusterSamples || recent.size < policy.establishedPace.minimumClusterSamples) return null
    val delta = median(recent) - median(preceding)
    if (abs(delta) < threshold) return null
    memory.lastLapFeedbackAtLap = lap
    return CoachingObservation(if (delta < 0) "Your pace is improving right now" else "Your pace has dropped over the last few laps", priority = 2)
  }

  private fun sectorTrendObservation(
    driver: TimingRow,
    lap: Int,
    memory: ObjectiveMemory,
    chattiness: CoachingChattiness,
  ): CoachingObservation? {
    if (lap - memory.lastLapFeedbackAtLap < lapCooldown(chattiness)) return null
    val trend =
      (1..3).mapNotNull { sector ->
        val samples = sectorSamples(driver, sector)
        if (samples.size < policy.establishedPace.minimumClusterSamples * 2) return@mapNotNull null
        val recent = samples.takeLast(policy.recentWindowSize)
        val preceding = samples.dropLast(recent.size).takeLast(policy.recentWindowSize)
        if (recent.size < policy.establishedPace.minimumClusterSamples || preceding.size < policy.establishedPace.minimumClusterSamples) return@mapNotNull null
        val delta = median(recent) - median(preceding)
        if (abs(delta) < policy.effectiveDeltaMs(samples)) null else SectorTrend(sector, delta)
      }.maxByOrNull { abs(it.deltaMs) } ?: return null
    memory.lastLapFeedbackAtLap = lap
    val direction = if (trend.deltaMs < 0) "is improving" else "has dropped"
    return CoachingObservation(
      "Sector ${sectorName(trend.sector)} pace $direction by ${formatSpokenDeltaMagnitude(abs(trend.deltaMs))}",
      trend.sector,
      priority = 2,
    )
  }

  private fun theoreticalInsight(
    driver: TimingRow,
    lap: Int,
    threshold: Long,
    memory: ObjectiveMemory,
    chattiness: CoachingChattiness,
  ): CoachingObservation? {
    if (lap - memory.lastTheoreticalInsightAtLap < theoreticalCooldown(chattiness)) return null
    val laps = driver.lapHistory
    val theoretical =
      listOf(
        laps.mapNotNull { it.sector1Ms }.minOrNull(),
        laps.mapNotNull { it.sector2Ms }.minOrNull(),
        laps.mapNotNull { it.sector3Ms }.minOrNull(),
      ).takeIf { it.all { value -> value != null } }?.sumOf { requireNotNull(it) } ?: return null
    val bestLap = laps.minOfOrNull { it.lapMs } ?: return null
    if (bestLap - theoretical < threshold) return null
    memory.lastTheoreticalInsightAtLap = lap
    return CoachingObservation(
      "Your best sectors make ${formatSpokenHundredths(theoretical)}. The pace is there to put the lap together",
      priority = 1,
    )
  }

  private fun opportunities(driver: TimingRow): List<SectorOpportunity> =
    (1..3).mapNotNull { sector ->
      val samples = sectorSamples(driver, sector)
      if (samples.size < policy.establishedPace.minimumClusterSamples) return@mapNotNull null
      val established = establishedFastPace(samples, policy.establishedPace) ?: return@mapNotNull null
      val recent = samples.takeLast(policy.recentWindowSize).takeIf { it.size >= policy.establishedPace.minimumClusterSamples } ?: return@mapNotNull null
      val recentMedian = median(recent)
      val gap = recentMedian - established
      if (gap < policy.effectiveDeltaMs(samples)) return@mapNotNull null
      SectorOpportunity(sector, recentMedian, established, gap)
    }

  private fun sectorSamples(
    driver: TimingRow,
    sector: Int,
    includeCurrent: Long? = null,
    beforeLap: Int? = null,
  ): List<Long> {
    val values =
      driver.lapHistory.sortedBy { it.lap }.filter { beforeLap == null || it.lap < beforeLap }.mapNotNull { lap ->
        when (sector) {
          1 -> lap.sector1Ms
          2 -> lap.sector2Ms
          else -> lap.sector3Ms
        }
      }
    return if (includeCurrent != null && values.lastOrNull() != includeCurrent) values + includeCurrent else values
  }

  private fun ObjectiveMemory.toUi() =
    CoachingObjectiveUiState(
      sector = objective.sector,
      opportunityMs = objective.opportunityAtStartMs,
      status = objective.status,
    )

  private fun sectorCooldown(chattiness: CoachingChattiness): Int =
    when (chattiness) {
      CoachingChattiness.Low -> policy.sectorCooldownAttempts
      CoachingChattiness.Medium -> (policy.sectorCooldownAttempts - 1).coerceAtLeast(1)
      CoachingChattiness.High -> 1
    }

  private fun lapCooldown(chattiness: CoachingChattiness): Int =
    when (chattiness) {
      CoachingChattiness.Low -> policy.lapCooldownLaps
      CoachingChattiness.Medium -> (policy.lapCooldownLaps - 1).coerceAtLeast(1)
      CoachingChattiness.High -> 1
    }

  private fun theoreticalCooldown(chattiness: CoachingChattiness): Int =
    when (chattiness) {
      CoachingChattiness.Low -> policy.lapCooldownLaps * 2
      CoachingChattiness.Medium -> policy.lapCooldownLaps
      CoachingChattiness.High -> policy.lapCooldownLaps.coerceAtLeast(1)
    }
}

private data class SectorOpportunity(val sector: Int, val recentMedianMs: Long, val establishedFastMs: Long, val gapMs: Long)
private data class SectorTrend(val sector: Int, val deltaMs: Long)

private fun sectorName(sector: Int): String = when (sector) { 1 -> "one"; 2 -> "two"; else -> "three" }
