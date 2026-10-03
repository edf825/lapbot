package com.example.lapbot.service

import com.example.lapbot.data.AnnouncementVoiceGender
import com.example.lapbot.data.AnnouncementSettings
import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.LapTimelineEntry
import com.example.lapbot.data.TimingRow
import com.example.lapbot.data.TimingUiState
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

class TimingStreamServiceTest {
  @Test
  fun mediaVolumeLevelsMapAcrossDeviceSteps() {
    assertEquals(0, mediaVolumeStep(0, 15))
    assertEquals(2, mediaVolumeStep(1, 15))
    assertEquals(8, mediaVolumeStep(5, 15))
    assertEquals(15, mediaVolumeStep(10, 15))
    assertEquals(1, mediaVolumeStep(1, 1))
    assertEquals(0, mediaVolumeLevel(0, 15))
    assertEquals(5, mediaVolumeLevel(8, 15))
    assertEquals(10, mediaVolumeLevel(15, 15))
  }

  @Test
  fun coachingDetailVoiceCommandsMoveOneLevelAndClampAtTheEnds() {
    assertEquals(
      CoachingChattiness.Medium,
      adjustCoachingDetail(CoachingChattiness.Low, CoachingDetailDirection.More),
    )
    assertEquals(
      CoachingChattiness.High,
      adjustCoachingDetail(CoachingChattiness.Medium, CoachingDetailDirection.More),
    )
    assertEquals(
      CoachingChattiness.High,
      adjustCoachingDetail(CoachingChattiness.High, CoachingDetailDirection.More),
    )
    assertEquals(
      CoachingChattiness.Medium,
      adjustCoachingDetail(CoachingChattiness.High, CoachingDetailDirection.Less),
    )
    assertEquals(
      CoachingChattiness.Low,
      adjustCoachingDetail(CoachingChattiness.Medium, CoachingDetailDirection.Less),
    )
    assertEquals(
      CoachingChattiness.Low,
      adjustCoachingDetail(CoachingChattiness.Low, CoachingDetailDirection.Less),
    )
    assertEquals("Coaching detail, low", formatCoachingDetailConfirmation(CoachingChattiness.Low))
    assertEquals("Coaching detail, mid", formatCoachingDetailConfirmation(CoachingChattiness.Medium))
    assertEquals("Coaching detail, high", formatCoachingDetailConfirmation(CoachingChattiness.High))
  }

  @Test
  fun voiceGapCommandUsesCurrentTimingAndKartNumberPreference() {
    val rows =
      listOf(
        voiceGapRow("12", position = 3, gapToLeaderMs = 2_000),
        voiceGapRow("8", position = 4, gapToLeaderMs = 3_200),
        voiceGapRow("27", position = 5, gapToLeaderMs = 3_530),
      )
    val state =
      TimingUiState(
        status = ConnectionStatus.Connected,
        selectedKartNumber = "8",
        rows = rows,
        announcementSettings = AnnouncementSettings(speakGaps = false, speakGapKartNumbers = true),
      )

    assertEquals(
      listOf("Gap to P3, kart 12, 1 point 20", "Gap to P5, kart 27, point 33"),
      formatGapVoiceCommandSections(state),
    )
  }

  @Test
  fun voiceGapCommandIsIndependentOfAutomaticGapAnnouncements() {
    val rows =
      listOf(
        voiceGapRow("3", position = 3, gapToLeaderMs = 2_000),
        voiceGapRow("4", position = 4, gapToLeaderMs = 3_200),
      )

    assertEquals(
      listOf("Gap to P3, 1 point 20"),
      formatGapVoiceCommandSections(
        TimingUiState(
          status = ConnectionStatus.Connected,
          selectedKartNumber = "4",
          rows = rows,
          announcementSettings = AnnouncementSettings(speakGaps = false),
        ),
      ),
    )
  }

  @Test
  fun voiceGapCommandDegradesClearlyWithoutAUsableSession() {
    assertEquals(
      listOf("Live timing is not connected"),
      formatGapVoiceCommandSections(TimingUiState()),
    )
    assertEquals(
      listOf("Pick a driver in focus first"),
      formatGapVoiceCommandSections(TimingUiState(status = ConnectionStatus.Connected)),
    )
  }

  @Test
  fun statusSpeaksPositionAndBothAdjacentGapsWithKartNumbers() {
    val state =
      TimingUiState(
        status = ConnectionStatus.Connected,
        selectedKartNumber = "8",
        rows =
          listOf(
            voiceGapRow("12", position = 3, gapToLeaderMs = 2_000),
            voiceGapRow("8", position = 4, gapToLeaderMs = 3_200),
            voiceGapRow("27", position = 5, gapToLeaderMs = 3_530),
          ),
        announcementSettings = AnnouncementSettings(speakGaps = false, speakGapKartNumbers = false),
      )

    assertEquals(
      listOf("Position P4", "Gap to P3, kart 12, 1 point 20", "Gap to P5, kart 27, point 33"),
      formatRaceStatusVoiceCommandSections(state),
    )
  }

  @Test
  fun statusSpeaksAvailableSideAtTheFrontOfTheField() {
    val state =
      TimingUiState(
        status = ConnectionStatus.Connected,
        selectedKartNumber = "8",
        rows =
          listOf(
            voiceGapRow("8", position = 1, gapToLeaderMs = 0),
            voiceGapRow("27", position = 2, gapToLeaderMs = 330),
          ),
      )

    assertEquals(
      listOf("Position P1", "Gap to P2, kart 27, point 33"),
      formatRaceStatusVoiceCommandSections(state),
    )
  }

  @Test
  fun statusKeepsPositionWhenGapsAreUnavailable() {
    val selected = TimingRow(id = "8", number = "8", position = 4, lap = 6)
    val state =
      TimingUiState(
        status = ConnectionStatus.Connected,
        selectedKartNumber = "8",
        rows = listOf(TimingRow(id = "12", number = "12", position = 3, lap = 6), selected),
      )

    assertEquals(
      listOf("Position P4", "Gaps are not available yet"),
      formatRaceStatusVoiceCommandSections(state),
    )
    assertEquals(
      listOf("Position P4", "Gap information is not available for this track"),
      formatRaceStatusVoiceCommandSections(state.copy(supportsGaps = false)),
    )
  }

  @Test
  fun statusExplainsMissingConnectionOrFocus() {
    assertEquals(
      listOf("Live timing is not connected"),
      formatRaceStatusVoiceCommandSections(TimingUiState()),
    )
    assertEquals(
      listOf("Pick a driver in focus first"),
      formatRaceStatusVoiceCommandSections(TimingUiState(status = ConnectionStatus.Connected)),
    )
  }

  @Test
  fun sectorTimingSpeaksOnlyTwoDecimalTimeAndDetectsNewPersonalBest() {
    val driver =
      TimingRow(
        id = "driver-5",
        lap = 3,
        sector1Ms = 18_549,
        lapHistory =
          listOf(
            com.example.lapbot.data.LapHistoryEntry(2, 55_000, sector1Ms = 18_700),
            com.example.lapbot.data.LapHistoryEntry(1, 56_000, sector1Ms = 18_900),
          ),
      )

    val delta = requireNotNull(sectorDeltaToPersonalBest(driver, 1))

    assertEquals(-151L, delta.deltaMs)
    assertTrue(delta.isNewBest)
    assertEquals("18 point 54, new PB", formatSectorDeltaAnnouncement(delta))
    assertEquals("18 point 54", formatSectorDeltaAnnouncement(delta, includeBestComparison = false))
  }

  private fun voiceGapRow(number: String, position: Int, gapToLeaderMs: Long) =
    TimingRow(
      id = number,
      number = number,
      position = position,
      lap = 6,
      gapToLeaderMs = gapToLeaderMs,
      gapRecordedAtLap = 6,
    )

  @Test
  fun equalOrSlowerSectorReportsItsPersonalBestDeficit() {
    val driver =
      TimingRow(
        id = "driver-5",
        lap = 3,
        sector2Ms = 20_250,
        lapHistory = listOf(com.example.lapbot.data.LapHistoryEntry(2, 55_000, sector2Ms = 20_000)),
      )

    val delta = requireNotNull(sectorDeltaToPersonalBest(driver, 2))

    assertEquals(250L, delta.deltaMs)
    assertFalse(delta.isNewBest)
    assertEquals("20 point 25, point 25 off best", formatSectorDeltaAnnouncement(delta))
  }

  @Test
  fun equalSectorReportsZeroOffBest() {
    val driver =
      TimingRow(
        id = "driver-5",
        lap = 3,
        sector2Ms = 20_000,
        lapHistory = listOf(com.example.lapbot.data.LapHistoryEntry(2, 55_000, sector2Ms = 20_000)),
      )

    val delta = requireNotNull(sectorDeltaToPersonalBest(driver, 2))

    assertEquals("20 point 00, point 00 off best", formatSectorDeltaAnnouncement(delta))
  }

  @Test
  fun completedLapAlwaysSelectsOneCue() {
    assertEquals(SectorSound.Best, lapCompletionSound(-1))
    assertEquals(SectorSound.Standard, lapCompletionSound(0))
    assertEquals(SectorSound.Standard, lapCompletionSound(120))
    assertEquals(SectorSound.Standard, lapCompletionSound(null))
  }

  @Test
  fun sectorPersonalBestDeficitRespectsSpeakBestComparisonSetting() {
    val delta = SectorDelta(sector = 1, sectorTimeMs = 16_420, deltaMs = 120)

    assertEquals("16 point 42", formatSectorDeltaAnnouncement(delta, includeBestComparison = false))
  }

  @Test
  fun googleBritishVoiceNames_areMappedToGender() {
    assertEquals(AnnouncementVoiceGender.Female, inferVoiceGender("en-gb-x-gba-local"))
    assertEquals(AnnouncementVoiceGender.Male, inferVoiceGender("en-gb-x-gbb-network"))
    assertEquals(AnnouncementVoiceGender.Female, inferVoiceGender("en_GB_female_1-local"))
    assertEquals(null, inferVoiceGender("en-gb-default"))
  }

  @Test
  fun announcementSections_preserveNaturalPauseBoundaries() {
    assertEquals(
      listOf("Great lap", "52 point 23", "Point 22 slower than last", "Biggest gain, sector two, point 18"),
      formatLapAnnouncementSections(
        lapTimeMs = 52_239,
        lastDeltaMs = 220,
        sectorInsight = "Biggest gain, sector two, point 18",
        encouragement = "Great lap",
      ),
    )
  }

  @Test
  fun lapAnnouncementUsesTotalSecondsAndTwoDecimalPlacesWithoutRounding() {
    assertEquals("61 point 49", formatLapAnnouncement(61_499))
    assertEquals("52 point 00", formatLapAnnouncement(52_000))
  }

  @Test
  fun lapAnnouncementIncludesSignedLastAndBestDeltas() {
    assertEquals(
      "52 point 23. Point 22 slower than last. Point 57 off your best",
      formatLapAnnouncement(52_239, lastDeltaMs = 220, bestDeltaMs = 570),
    )
    assertEquals(
      "51 point 90. Point 33 quicker than last. New best by point 05",
      formatLapAnnouncement(51_909, lastDeltaMs = -339, bestDeltaMs = -59),
    )
    assertEquals(
      "52 point 00. Same as last. 1 point 23 off your best",
      formatLapAnnouncement(52_000, lastDeltaMs = 0, bestDeltaMs = 1_239),
    )
    assertEquals(
      "52 point 00. Same as last. Matches your best",
      formatLapAnnouncement(52_000, lastDeltaMs = 0, bestDeltaMs = 0),
    )
  }

  @Test
  fun biggestSectorInsightReportsTheLargestAbsoluteChange() {
    val driver =
      TimingRow(
        id = "5",
        lapTimeline =
          listOf(
            LapTimelineEntry(10, 51_900, 20_800, 15_300, 15_800),
            LapTimelineEntry(9, 52_100, 21_100, 15_200, 15_800),
          ),
      )

    val analysis = analyzeSectorChanges(driver, 10)
    val insight = CoachingPhraseBank().sectorInsight("5", requireNotNull(analysis.biggestChange))

    assertTrue(analysis.currentLapComplete)
    assertEquals(SectorChange(1, -300), analysis.biggestChange)
    assertTrue(requireNotNull(insight).contains("sector one"))
    assertTrue(insight.contains("point 30"))
  }

  @Test
  fun biggestSectorInsightRequiresCompleteCurrentAndPreviousSectors() {
    val driver =
      TimingRow(
        id = "5",
        lapTimeline =
          listOf(
            LapTimelineEntry(10, 51_900, 20_800, 15_300, null),
            LapTimelineEntry(9, 52_100, 21_100, 15_200, 15_800),
          ),
      )

    val analysis = analyzeSectorChanges(driver, 10)

    assertFalse(analysis.currentLapComplete)
    assertNull(analysis.biggestChange)
  }

  @Test
  fun coachingOmitsDriverNameAndRotatesWithoutImmediateRepetition() {
    val phrases = CoachingPhraseBank()
    val improvementMessages =
      (1..12).map {
        phrases.encouragement("driver-5", lastDeltaMs = -120, bestDeltaMs = 400)
      }

    assertEquals(12, improvementMessages.toSet().size)
    assertTrue(improvementMessages.none { it.contains("John") })
    assertTrue(improvementMessages.none { it.contains("Reeves") })
    assertTrue(improvementMessages.none { it.contains("{name}") })
  }

  @Test
  fun immaterialSectorChangesAreNotSpoken() {
    assertNull(CoachingPhraseBank().sectorInsight("driver-5", SectorChange(2, 99)))
  }

  @Test
  fun encouragementComesBeforeTheLapTimeAndComparisons() {
    assertEquals(
      "Brilliant lap. 51 point 90. Point 33 quicker than last. New best by point 05",
      formatLapAnnouncement(
        lapTimeMs = 51_909,
        lastDeltaMs = -339,
        bestDeltaMs = -59,
        encouragement = "Brilliant lap",
      ),
    )
  }
}
