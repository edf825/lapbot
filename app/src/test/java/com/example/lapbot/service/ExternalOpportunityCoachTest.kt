package com.example.lapbot.service

import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.TimingRow
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ExternalOpportunityCoachTest {
  @Test
  fun opportunityRequiresTwoEvaluationsAndDetailControlsWording() {
    CoachingChattiness.entries.forEach { detail ->
      val coach = ExternalOpportunityCoach()
      val focused = row("focused", "8", 31_930, 21_200, 26_000)
      val field = listOf(focused, row("benchmark", "1", 31_000, 20_000, 25_000))

      assertNull(coach.onLap(focused, field, 5, detail).observation)
      val established = coach.onLap(focused, field, 6, detail)

      assertEquals(2, established.ui.sector)
      assertNotNull(established.observation)
      when (detail) {
        CoachingChattiness.Low -> assertTrue(established.observation!!.text.contains("clearest relative opportunity"))
        CoachingChattiness.Medium -> assertTrue(established.observation!!.text.contains("additional loss"))
        CoachingChattiness.High -> assertTrue(established.observation!!.text.contains("6 percent"))
      }
    }
  }

  @Test
  fun oneFastAttemptDoesNotTriggerProgressButThreeRepeatableAttemptsDo() {
    val coach = ExternalOpportunityCoach()
    val benchmark = row("benchmark", "1", 31_000, 20_000, 25_000)
    var focused = row("focused", "8", 31_930, 21_200, 26_000)
    coach.onLap(focused, listOf(focused, benchmark), 5, CoachingChattiness.High)
    coach.onLap(focused, listOf(focused, benchmark), 6, CoachingChattiness.High)

    focused = focused.withLap(7, 31_930, 20_800, 26_000)
    assertNull(coach.onLap(focused, listOf(focused, benchmark), 7, CoachingChattiness.High).observation)
    focused = focused.withLap(8, 31_940, 20_820, 26_010)
    assertNull(coach.onLap(focused, listOf(focused, benchmark), 8, CoachingChattiness.High).observation)
    focused = focused.withLap(9, 31_920, 20_810, 25_990)
    val progress = coach.onLap(focused, listOf(focused, benchmark), 9, CoachingChattiness.High)

    assertTrue(requireNotNull(progress.observation).text.contains("additional loss has reduced"))
    assertEquals(400L, progress.ui.initialOpportunityMs)
    assertTrue(requireNotNull(progress.ui.opportunityMs) < requireNotNull(progress.ui.initialOpportunityMs))
  }

  @Test
  fun changingFocusedDriverDiscardsThePreviousDriversOpportunity() {
    val coach = ExternalOpportunityCoach()
    val benchmark = row("benchmark", "1", 31_000, 20_000, 25_000)
    val first = row("first", "8", 31_930, 21_200, 26_000)
    coach.onLap(first, listOf(first, benchmark), 5, CoachingChattiness.High)
    assertEquals(2, coach.onLap(first, listOf(first, benchmark), 6, CoachingChattiness.High).ui.sector)

    val second = row("second", "9", 31_300, 20_300, 25_300)
    val switched = coach.onLap(second, listOf(second, benchmark), 6, CoachingChattiness.High)

    assertNull(switched.observation)
    assertNull(switched.ui.sector)
  }

  @Test
  fun benchmarkCohortChangeDoesNotImmediatelyProduceCoachingChurn() {
    val coach = ExternalOpportunityCoach()
    val focused = row("focused", "8", 31_930, 21_200, 26_000)
    val original = row("original", "1", 31_000, 20_000, 25_000)
    val challenger = row("challenger", "2", 30_000, 19_000, 24_000)
    coach.onLap(focused, listOf(focused, original), 5, CoachingChattiness.High)
    coach.onLap(focused, listOf(focused, original), 6, CoachingChattiness.High)

    val firstChangedEvaluation = coach.onLap(focused, listOf(focused, challenger), 7, CoachingChattiness.High)
    val secondChangedEvaluation = coach.onLap(focused, listOf(focused, challenger), 8, CoachingChattiness.High)

    assertNull(firstChangedEvaluation.observation)
    assertNull(secondChangedEvaluation.observation)
  }

  @Test
  fun externalOpportunityWordingNeverPromisesTheWholeLeaderGapIsRecoverable() {
    CoachingChattiness.entries.forEach { detail ->
      val coach = ExternalOpportunityCoach()
      val focused = row("focused", "8", 31_930, 21_200, 26_000)
      val field = listOf(focused, row("benchmark", "1", 31_000, 20_000, 25_000))
      coach.onLap(focused, field, 5, detail)

      val message = requireNotNull(coach.onLap(focused, field, 6, detail).observation).text

      assertTrue(!message.contains("can gain", ignoreCase = true))
      assertTrue(!message.contains("recover", ignoreCase = true))
    }
  }

  private fun row(id: String, number: String, sector1: Long, sector2: Long, sector3: Long): TimingRow {
    val jitters = listOf(-20L, 0L, 20L, -10L, 10L)
    return TimingRow(
      id = id,
      number = number,
      lapHistory =
        jitters.mapIndexed { index, jitter ->
          lap(index + 1, sector1 + jitter, sector2 + jitter, sector3 + jitter)
        }.reversed(),
    )
  }

  private fun TimingRow.withLap(number: Int, sector1: Long, sector2: Long, sector3: Long): TimingRow =
    copy(lapHistory = listOf(lap(number, sector1, sector2, sector3)) + lapHistory)

  private fun lap(number: Int, sector1: Long, sector2: Long, sector3: Long) =
    LapHistoryEntry(number, sector1 + sector2 + sector3, sector1, sector2, sector3)
}
