package com.example.lapbot.data

/** A compact projection of the engineer's richer session objective. */
enum class CoachingObjectiveStatus {
  CollectingData,
  Identified,
  Working,
  PromisingImprovement,
  ImprovementConfirmed,
  ReadyToReassess,
}

data class CoachingObjectiveUiState(
  val sector: Int? = null,
  val opportunityMs: Long? = null,
  val status: CoachingObjectiveStatus = CoachingObjectiveStatus.CollectingData,
)

enum class RelativeOpportunityStatus {
  CollectingData,
  Identified,
  Working,
  Improving,
  Resolved,
}

enum class OpportunityConfidence {
  Established,
  Strong,
}

/** Explainable projection of the session-relative opportunity used by Pitlane and debug tools. */
data class RelativeOpportunityUiState(
  val sector: Int? = null,
  val benchmarkKartNumbers: List<String> = emptyList(),
  val driverPaceMs: Long? = null,
  val benchmarkPaceMs: Long? = null,
  val relativeDeficitPercent: Double? = null,
  val typicalDeficitPercent: Double? = null,
  val excessDeficitPercentagePoints: Double? = null,
  val initialOpportunityMs: Long? = null,
  val opportunityMs: Long? = null,
  val driverSampleCount: Int = 0,
  val benchmarkSampleCount: Int = 0,
  val confidence: OpportunityConfidence? = null,
  val status: RelativeOpportunityStatus = RelativeOpportunityStatus.CollectingData,
  val frontRunningSectors: Set<Int> = emptySet(),
)
