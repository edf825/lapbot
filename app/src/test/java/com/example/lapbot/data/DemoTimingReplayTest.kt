package com.example.lapbot.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class DemoTimingReplayTest {
  @Test
  fun replayUsesSectorProportionalTwoTimesCadence() {
    val replay = DemoTimingReplay()

    assertTrue(replay.initialDelayMs() in 10_000L..11_000L)
    val sector1 = replay.advance()
    val sector2 = replay.advance()
    val lapTime = replay.advance()
    val sector3 = replay.advance()

    assertTrue(sector1.nextDelayMs in 7_000L..8_500L)
    assertTrue(sector2.nextDelayMs in 7_000L..8_500L)
    assertEquals(250L, lapTime.nextDelayMs)
    assertTrue(sector3.nextDelayMs in 10_000L..11_000L)
    assertTrue(
      sector1.nextDelayMs + sector2.nextDelayMs + lapTime.nextDelayMs + sector3.nextDelayMs in
        25_000L..27_500L,
    )
  }

  @Test
  fun replayStartsWithJohnOnKartFiveAndAnIncompleteNextLap() {
    val john = DemoTimingReplay().initialRows().single { it.number == "5" }

    assertEquals("John Reeves", john.name)
    assertEquals(9, john.lap)
    assertNull(john.lapMs)
    assertEquals(8, john.recentCompletedLap)
    assertEquals(8, john.lapHistory.size)
    assertTrue(john.lapHistory.take(3).all { it.sector2Ms == 15_800L })
    assertTrue(john.lapHistory.takeLast(3).all { it.sector2Ms == 15_500L })
  }

  @Test
  fun totalArrivesBeforeFinalSectorAndLapOnlyBecomesValidAfterSectorThree() {
    val replay = DemoTimingReplay()
    replay.initialRows()

    assertEquals(DemoPhase.Sector1, replay.advance().phase)
    assertEquals(DemoPhase.Sector2, replay.advance().phase)
    val totalFrame = replay.advance()
    val partialJohn = totalFrame.rows.single { it.number == "5" }

    assertEquals(DemoPhase.LapTime, totalFrame.phase)
    assertEquals(52_831L, partialJohn.lapMs)
    assertNull(partialJohn.sector3Ms)
    assertEquals(8, partialJohn.lapHistory.size)

    val completeFrame = replay.advance()
    val completeJohn = completeFrame.rows.single { it.number == "5" }
    val completedLap = completeJohn.lapHistory.first()

    assertEquals(DemoPhase.Sector3, completeFrame.phase)
    assertEquals(9, completedLap.lap)
    assertEquals(completedLap.lapMs, completedLap.sector1Ms!! + completedLap.sector2Ms!! + completedLap.sector3Ms!!)
    assertTrue(completeFrame.records.any { it.contains("John Reeves") })
  }
}
