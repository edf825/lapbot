package com.example.lapbot.service

import com.example.lapbot.data.TimingRow
import java.util.ArrayDeque
import kotlin.math.abs

internal class CoachingPhraseBank {
  private val cursors = mutableMapOf<String, Int>()
  private val recentTemplateIds = ArrayDeque<String>()

  fun encouragement(driverId: String, lastDeltaMs: Long?, bestDeltaMs: Long?): String {
    val category =
      when {
        bestDeltaMs != null && bestDeltaMs <= -SPOKEN_CHANGE_MS -> EncouragementCategory.NewBest
        lastDeltaMs != null && lastDeltaMs <= -SPOKEN_CHANGE_MS -> EncouragementCategory.Improved
        lastDeltaMs != null && abs(lastDeltaMs) < MATERIAL_SECTOR_CHANGE_MS -> EncouragementCategory.Consistent
        lastDeltaMs != null -> EncouragementCategory.Slower
        else -> EncouragementCategory.Baseline
      }
    return select(driverId, category.name, category.templates)
  }

  fun sectorInsight(driverId: String, change: SectorChange): String? {
    if (abs(change.deltaMs) < MATERIAL_SECTOR_CHANGE_MS) return null
    val category = if (change.deltaMs < 0) SectorCategory.Gain else SectorCategory.Loss
    return select(driverId, category.name, category.templates)
      .replace("{sector}", change.sectorName)
      .replace("{delta}", formatSpokenDeltaMagnitude(abs(change.deltaMs)))
  }

  fun consistencyRecognition(driverId: String, sector: Int, consecutive: Boolean): String {
    val category =
      if (consecutive) ConsistencyCategory.Consistent
      else ConsistencyCategory.InWindow
    return select(driverId, category.name, category.templates)
      .replace("{sector}", spokenSectorName(sector))
  }

  private fun select(driverId: String, category: String, templates: List<String>): String {
    val key = "$driverId:$category"
    val start = cursors[key] ?: Math.floorMod(key.hashCode(), templates.size)
    var selectedIndex = start
    for (offset in templates.indices) {
      val candidate = (start + offset) % templates.size
      if ("$category:$candidate" !in recentTemplateIds) {
        selectedIndex = candidate
        break
      }
    }
    cursors[key] = (selectedIndex + 1) % templates.size
    recentTemplateIds.addLast("$category:$selectedIndex")
    while (recentTemplateIds.size > RECENT_TEMPLATE_LIMIT) recentTemplateIds.removeFirst()
    return templates[selectedIndex]
  }
}

internal data class SectorAnalysis(
  val currentLapComplete: Boolean,
  val biggestChange: SectorChange? = null,
)

internal data class SectorChange(val sector: Int, val deltaMs: Long) {
  val sectorName: String
    get() = when (sector) {
      1 -> "one"
      2 -> "two"
      else -> "three"
    }
}

internal fun analyzeSectorChanges(driver: TimingRow, completedLap: Int): SectorAnalysis {
  val current = driver.lapTimeline.firstOrNull { it.lap == completedLap } ?: return SectorAnalysis(false)
  val currentSectors = listOf(current.sector1Ms, current.sector2Ms, current.sector3Ms)
  if (currentSectors.any { it == null }) return SectorAnalysis(false)
  val previous = driver.lapTimeline.firstOrNull { it.lap < completedLap } ?: return SectorAnalysis(true)
  val previousSectors = listOf(previous.sector1Ms, previous.sector2Ms, previous.sector3Ms)
  if (previousSectors.any { it == null }) return SectorAnalysis(true)
  val changes =
    currentSectors.indices.map { index ->
      SectorChange(index + 1, requireNotNull(currentSectors[index]) - requireNotNull(previousSectors[index]))
    }
  return SectorAnalysis(true, changes.maxBy { abs(it.deltaMs) })
}

private enum class EncouragementCategory(val templates: List<String>) {
  NewBest(
    listOf(
      "Brilliant lap",
      "That's your best yet",
      "Excellent work",
      "Superb lap",
      "Top work",
      "Great result",
      "That's the one",
      "Outstanding lap",
      "You've found more time",
      "Strongest lap yet",
      "Nicely done",
      "New benchmark",
    ),
  ),
  Improved(
    listOf(
      "Good step forward",
      "Nice improvement",
      "Good progress",
      "That's moving the right way",
      "You're building pace",
      "Good response",
      "That's better",
      "Nice gain",
      "Solid improvement",
      "You're getting quicker",
      "Well done",
      "Good lap",
    ),
  ),
  Consistent(
    listOf(
      "Good consistency",
      "Nice and steady",
      "That's repeatable",
      "Solid rhythm",
      "Another tidy lap",
      "You're holding the pace",
      "Very close again",
      "Good rhythm",
      "Keep that going",
      "Right in the window",
      "Nicely settled",
      "Another solid one",
    ),
  ),
  Slower(
    listOf(
      "Stay with it",
      "Reset and go again",
      "Keep working",
      "There's more to come",
      "Stay focused",
      "Keep building",
      "Use the next one",
      "Stay composed",
      "Find the rhythm again",
      "Keep your head up",
      "Next lap",
      "You've got this",
    ),
  ),
  Baseline(
    listOf(
      "You're underway",
      "Lap on the board",
      "Good start",
      "Let's build from there",
      "Baseline set",
      "That's the first one",
      "Session started",
      "We've got a reference",
    ),
  ),
}

private enum class SectorCategory(val templates: List<String>) {
  Gain(
    listOf(
      "Biggest gain, sector {sector}, {delta}",
      "Most time gained in sector {sector}, {delta}",
      "Sector {sector} gave the biggest gain, {delta}",
      "Strongest improvement, sector {sector}, {delta}",
      "Best gain came in sector {sector}, {delta}",
      "You found most time in sector {sector}, {delta}",
      "Sector {sector} improved the most, {delta}",
      "Main gain, sector {sector}, {delta}",
      "Biggest step forward, sector {sector}, {delta}",
      "Sector {sector} was the strongest gain, {delta}",
    ),
  ),
  Loss(
    listOf(
      "Biggest loss, sector {sector}, {delta}",
      "Most time lost in sector {sector}, {delta}",
      "Sector {sector} had the biggest loss, {delta}",
      "Main loss, sector {sector}, {delta}",
      "Sector {sector} moved back the most, {delta}",
      "Biggest drop, sector {sector}, {delta}",
      "Sector to recover, sector {sector}, {delta}",
      "Sector {sector} was furthest off, {delta}",
      "Focus area, sector {sector}, {delta} lost",
      "Most time to recover is in sector {sector}, {delta}",
    ),
  ),
}

private enum class ConsistencyCategory(val templates: List<String>) {
  InWindow(
    listOf(
      "Sector {sector} is within a tenth of your repeatable best. Keep pushing",
      "You're within a tenth in sector {sector}. Keep building on that",
      "Sector {sector}, within a tenth of your repeatable best. Hold that level",
      "That's sector {sector} within a tenth. Keep it there",
    ),
  ),
  Consistent(
    listOf(
      "You are consistently within a tenth in sector {sector}. Keep pushing",
      "Sector {sector} is consistently within a tenth. Keep that standard",
      "Another sector {sector} within a tenth. That consistency is strong",
      "You keep putting sector {sector} within a tenth. That's consistent. Stay on it",
    ),
  ),
}

private const val SPOKEN_CHANGE_MS = 10L
private const val MATERIAL_SECTOR_CHANGE_MS = 100L
private const val RECENT_TEMPLATE_LIMIT = 3

private fun spokenSectorName(sector: Int): String =
  when (sector) {
    1 -> "one"
    2 -> "two"
    else -> "three"
  }
