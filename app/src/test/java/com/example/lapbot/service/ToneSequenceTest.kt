package com.example.lapbot.service

import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.TimingRow
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.ToneMetric
import com.example.lapbot.data.ToneSettings
import com.example.lapbot.data.canonicalKartNumber
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import kotlin.math.pow
import org.junit.Test

class ToneSequenceTest {
  @Test
  fun lapOnlyTracksProduceOnlyReferenceAndLapTones() {
    val state =
      TimingUiState(
        supportsSectors = false,
        selectedKartNumber = "5",
        rows =
          listOf(
            TimingRow(
              id = "5",
              number = "5",
              lapHistory = listOf(LapHistoryEntry(2, 52_000), LapHistoryEntry(1, 52_500)),
            ),
          ),
        toneSettings = ToneSettings(metric = ToneMetric.PreviousLap),
      )

    val sequence = requireNotNull(buildToneSequence(state))

    assertEquals(2, sequence.frequenciesHz.size)
    assertEquals(listOf(200, 300), sequence.durationsMs)
  }

  @Test
  fun kartNumbersAreNormalizedWithoutNameMatching() {
    assertEquals("7", canonicalKartNumber("007"))
    assertEquals("A7", canonicalKartNumber(" A7 "))
  }

  @Test
  fun fasterTimesProduceHigherSemitones() {
    val sequence = buildToneSequence(state(ToneMetric.PreviousLap)) ?: error("Expected tones")

    assertEquals(440.0, sequence.frequenciesHz[0], 0.01)
    assertEquals(440.0 * 2.0.pow(2.0 / 12.0), sequence.frequenciesHz[1], 0.01)
    assertEquals(440.0 * 2.0.pow(1.0 / 12.0), sequence.frequenciesHz[2], 0.01)
    assertEquals(440.0, sequence.frequenciesHz[3], 0.01)
    assertEquals(440.0 * 2.0.pow(1.0 / 12.0), sequence.frequenciesHz[4], 0.01)
  }

  @Test
  fun pitchIsCappedAtOneOctave() {
    val sequence = buildToneSequence(state(ToneMetric.DriverBest)) ?: error("Expected tones")

    assertEquals(220.0, sequence.frequenciesHz[1], 0.01)
  }

  @Test
  fun previousLapIsUnavailableBeforeSecondLapInMetricWindow() {
    val state = state(ToneMetric.PreviousLap).copy(metricsSinceLap = 10)

    assertNull(buildToneSequence(state))
  }

  @Test
  fun tonesAreSkippedWhenCompleteMetricsBelongToAnEarlierLap() {
    assertNull(buildToneSequence(state(ToneMetric.DriverBest), expectedLap = 11))
  }

  @Test
  fun tonesAreSkippedWhenPlaybackIsDisabled() {
    val state = state(ToneMetric.DriverBest).copy(toneSettings = ToneSettings(enabled = false))

    assertNull(buildToneSequence(state))
  }

  private fun state(metric: ToneMetric): TimingUiState =
    TimingUiState(
      selectedKartNumber = "12",
      toneSettings = ToneSettings(metric = metric),
      rows =
        listOf(
          TimingRow(
            id = "7",
            number = "12",
            bestLap = 8,
            bestLapMs = 58_000,
            bestLapSector1Ms = 19_000,
            bestLapSector2Ms = 19_000,
            bestLapSector3Ms = 20_000,
            lapHistory =
              listOf(
                LapHistoryEntry(10, 60_000, 20_000, 20_000, 20_000),
                LapHistoryEntry(9, 60_200, 20_100, 20_000, 20_100),
                LapHistoryEntry(8, 58_000, 19_000, 19_000, 20_000),
              ),
          ),
        ),
    )
}
