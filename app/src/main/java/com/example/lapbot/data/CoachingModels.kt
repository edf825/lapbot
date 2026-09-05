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
