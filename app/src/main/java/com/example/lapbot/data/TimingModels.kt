package com.example.lapbot.data

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class ConnectionStatus {
  Disconnected,
  Connecting,
  Connected,
  Reconnecting,
}

data class TimingTrack(
  val id: String,
  val label: String,
  val supportsSectors: Boolean,
  val supportsGaps: Boolean,
)

object TimingTracks {
  val BuckmorePark = TimingTrack("buckmore", "Buckmore Park", supportsSectors = true, supportsGaps = true)
  val DaytonaSandownParkGp =
    TimingTrack(
      "daytona-sandown-gp",
      "Daytona Sandown Park GP Circuit",
      supportsSectors = false,
      supportsGaps = true,
    )
  val TeamSportFarnborough =
    TimingTrack("teamsport-farnborough", "TeamSport Farnborough", supportsSectors = false, supportsGaps = true)
  val TeamSportLeicester =
    TimingTrack("teamsport-leicester", "TeamSport Leicester", supportsSectors = false, supportsGaps = true)
  val All = listOf(BuckmorePark, DaytonaSandownParkGp, TeamSportFarnborough, TeamSportLeicester)

  fun find(id: String?): TimingTrack? = All.firstOrNull { it.id == id }
}

data class ReconnectPolicy(
  val initialDelayMs: Long = 500,
  val maxDelayMs: Long = 60_000,
  val giveUpAfterMs: Long = 10 * 60_000,
)

enum class ToneMetric {
  PreviousLap,
  DriverBest,
  TheoreticalBest,
  RaceBest,
  BestRecent,
}

data class ToneSettings(
  val enabled: Boolean = true,
  val metric: ToneMetric = ToneMetric.DriverBest,
  val referenceDurationMs: Int = 200,
  val lapDurationMs: Int = 300,
  val sectorDurationMs: Int = 150,
)

enum class AnnouncementVoiceGender {
  Female,
  Male,
}

enum class CoachingChattiness {
  Low,
  Medium,
  High,
}

data class AnnouncementSettings(
  val speakLastComparison: Boolean = true,
  val speakBestComparison: Boolean = true,
  val speakGaps: Boolean = false,
  val speakGapKartNumbers: Boolean = false,
  val speakSectorDeltas: Boolean = false,
  val sectorTonesEnabled: Boolean = true,
  val speakCoaching: Boolean = false,
  val coachingChattiness: CoachingChattiness = CoachingChattiness.Low,
  val voiceGender: AnnouncementVoiceGender = AnnouncementVoiceGender.Female,
  val speechRate: Float = 0.97f,
)

data class TimingRow(
  val id: String,
  val number: String = "",
  val name: String = "",
  val position: Int? = null,
  val lap: Int? = null,
  val sector1Ms: Long? = null,
  val sector2Ms: Long? = null,
  val sector3Ms: Long? = null,
  val lapMs: Long? = null,
  val recentCompletedLap: Int? = null,
  val recentCompletedLapMs: Long? = null,
  val recentCompletedSector1Ms: Long? = null,
  val recentCompletedSector2Ms: Long? = null,
  val recentCompletedSector3Ms: Long? = null,
  val bestLap: Int? = null,
  val bestLapMs: Long? = null,
  val bestLapSector1Ms: Long? = null,
  val bestLapSector2Ms: Long? = null,
  val bestLapSector3Ms: Long? = null,
  val bestSector1Ms: Long? = null,
  val bestSector2Ms: Long? = null,
  val bestSector3Ms: Long? = null,
  val theoreticalBestMs: Long? = null,
  val lapHistory: List<LapHistoryEntry> = emptyList(),
  val lapTimeline: List<LapTimelineEntry> = emptyList(),
  /** Provider gap to the race leader at [gapRecordedAtLap], in milliseconds. */
  val gapToLeaderMs: Long? = null,
  /** Provider interval to the immediately preceding position, when supplied directly. */
  val gapToAheadMs: Long? = null,
  val gapRecordedAtLap: Int? = null,
)

data class LapHistoryEntry(
  val lap: Int,
  val lapMs: Long,
  val sector1Ms: Long? = null,
  val sector2Ms: Long? = null,
  val sector3Ms: Long? = null,
)

data class LapTimelineEntry(
  val lap: Int,
  val lapMs: Long? = null,
  val sector1Ms: Long? = null,
  val sector2Ms: Long? = null,
  val sector3Ms: Long? = null,
)

data class TimingUiState(
  val status: ConnectionStatus = ConnectionStatus.Disconnected,
  val isDemo: Boolean = false,
  val replayDescription: String? = null,
  val autoReconnect: Boolean = true,
  val selectedTrackId: String? = null,
  val sessionKey: String? = null,
  val supportsSectors: Boolean = true,
  val supportsGaps: Boolean = true,
  val rows: List<TimingRow> = emptyList(),
  val jsonTail: List<String> = emptyList(),
  val tailLimit: Int = 20,
  val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
  val selectedKartNumber: String? = null,
  val autoDetectDriverName: String? = null,
  val metricsSinceLap: Int? = null,
  val coachEnabled: Boolean = true,
  val listenForCommands: Boolean = false,
  val announcementSettings: AnnouncementSettings = AnnouncementSettings(),
  val coachingObjective: CoachingObjectiveUiState = CoachingObjectiveUiState(),
  val relativeOpportunity: RelativeOpportunityUiState = RelativeOpportunityUiState(),
  val toneSettings: ToneSettings = ToneSettings(),
  val error: String? = null,
)

interface TimingRepository : AutoCloseable {
  val state: StateFlow<TimingUiState>

  fun connect(trackId: String = TimingTracks.BuckmorePark.id)

  fun startDemo()

  fun disconnect()

  fun setAutoReconnect(enabled: Boolean)

  fun setTailLimit(limit: Int)

  fun setReconnectPolicy(policy: ReconnectPolicy)

  fun setSelectedKartNumber(kartNumber: String?)

  fun setAutoDetectDriverName(nameFragment: String?)

  fun setMetricsSinceLap(lap: Int?)

  fun setCoachEnabled(enabled: Boolean)

  fun setListenForCommands(enabled: Boolean)

  fun setAnnouncementSettings(settings: AnnouncementSettings)

  fun previewAnnouncement()

  fun setToneSettings(settings: ToneSettings)

  fun playTestTones()
}

fun TimingRow.metricLapsSince(sinceLap: Int?): List<LapHistoryEntry> {
  if (sinceLap == null) return lapHistory
  return lapHistory.filter { it.lap >= sinceLap }.ifEmpty { lapHistory.take(1) }
}

fun canonicalKartNumber(value: String?): String? {
  val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
  return trimmed.toIntOrNull()?.toString() ?: trimmed
}

fun canonicalDriverNameFragment(value: String?): String? =
  value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotEmpty)

/** Returns a driver only when the fragment identifies one unambiguous kart. */
fun findDriverByNameFragment(rows: List<TimingRow>, nameFragment: String?): TimingRow? {
  val fragment = canonicalDriverNameFragment(nameFragment)?.lowercase(Locale.ROOT) ?: return null
  val matches =
    rows
      .filter { it.name.lowercase(Locale.ROOT).contains(fragment) }
      .filter { canonicalKartNumber(it.number) != null }
      .distinctBy { canonicalKartNumber(it.number) }
  return matches.singleOrNull()
}
