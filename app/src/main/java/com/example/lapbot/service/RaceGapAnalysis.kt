package com.example.lapbot.service

import com.example.lapbot.data.TimingRow

internal data class PositionGap(
  val position: Int,
  val kartNumber: String,
  val milliseconds: Long,
)

internal data class AdjacentRaceGaps(
  val ahead: PositionGap? = null,
  val behind: PositionGap? = null,
  val isComplete: Boolean = true,
)

/**
 * Resolves gaps only from measurements that describe the same completed lap. Provider intervals
 * take priority, followed by provider gaps-to-leader and, for Alpha/Buckmore, accumulated race
 * time from complete lap histories. A missing or contradictory measurement is suppressed.
 */
internal fun calculateAdjacentRaceGaps(
  rows: List<TimingRow>,
  selected: TimingRow,
  completedLap: Int,
): AdjacentRaceGaps? {
  val selectedPosition = selected.position ?: return null
  val byPosition =
    rows.groupBy { it.position }.mapNotNull { (position, entries) ->
      if (position != null && entries.size == 1) position to entries.single() else null
    }.toMap()

  val ahead =
    if (selectedPosition > 1) {
      byPosition[selectedPosition - 1]?.let { row ->
        gapBetween(ahead = row, behind = selected, completedLap = completedLap)
          ?.let { PositionGap(selectedPosition - 1, row.number, it) }
      }
    } else null

  val behind =
    byPosition[selectedPosition + 1]?.let { row ->
      gapBetween(ahead = selected, behind = row, completedLap = completedLap)
        ?.let { PositionGap(selectedPosition + 1, row.number, it) }
    }

  val highestPosition = byPosition.keys.maxOrNull() ?: selectedPosition
  val expectsAhead = selectedPosition > 1
  val expectsBehind = selectedPosition < highestPosition
  return AdjacentRaceGaps(
    ahead = ahead,
    behind = behind,
    isComplete = (!expectsAhead || ahead != null) && (!expectsBehind || behind != null),
  ).takeIf { it.ahead != null || it.behind != null }
}

private fun gapBetween(ahead: TimingRow, behind: TimingRow, completedLap: Int): Long? {
  if (behind.gapRecordedAtLap == completedLap && behind.gapToAheadMs != null) {
    return behind.gapToAheadMs.takeIf { it >= 0 }
  }

  if (ahead.gapRecordedAtLap == completedLap && behind.gapRecordedAtLap == completedLap) {
    val aheadGap = ahead.gapToLeaderMs
    val behindGap = behind.gapToLeaderMs
    if (aheadGap != null && behindGap != null) return (behindGap - aheadGap).takeIf { it >= 0 }
  }

  val aheadElapsed = ahead.accumulatedRaceTime(completedLap) ?: return null
  val behindElapsed = behind.accumulatedRaceTime(completedLap) ?: return null
  return (behindElapsed - aheadElapsed).takeIf { it >= 0 }
}

private fun TimingRow.accumulatedRaceTime(completedLap: Int): Long? {
  if (lap != completedLap) return null
  val byLap = lapHistory.associateBy { it.lap }
  if (byLap.size < completedLap || (1..completedLap).any { it !in byLap }) return null
  return (1..completedLap).sumOf { requireNotNull(byLap[it]).lapMs }
}

internal fun formatGapAnnouncementSections(
  gaps: AdjacentRaceGaps?,
  includeKartNumbers: Boolean = false,
): List<String> =
  buildList {
    gaps?.ahead?.let { add(it.spoken(includeKartNumbers)) }
    gaps?.behind?.let { add(it.spoken(includeKartNumbers)) }
  }

private fun PositionGap.spoken(includeKartNumber: Boolean): String =
  buildString {
    append("Gap to P$position")
    if (includeKartNumber && kartNumber.isNotBlank()) append(", kart $kartNumber")
    append(", ${formatSpokenDeltaMagnitude(milliseconds)}")
  }
