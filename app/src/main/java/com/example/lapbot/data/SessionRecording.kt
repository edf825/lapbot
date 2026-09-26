package com.example.lapbot.data

import android.content.Context
import com.example.lapbot.service.calculateAdjacentRaceGaps
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RecordedLap(
  val lap: Int,
  val lapTimeMs: Long,
  val sector1Ms: Long? = null,
  val sector2Ms: Long? = null,
  val sector3Ms: Long? = null,
  val position: Int? = null,
  val gapAheadMs: Long? = null,
  val gapBehindMs: Long? = null,
)

@Serializable
data class RecordedDriver(
  val id: String,
  val name: String,
  val kartNumber: String,
  val finalPosition: Int? = null,
  val laps: List<RecordedLap> = emptyList(),
)

@Serializable
data class RecordedEngineerEvent(
  val recordedAtEpochMs: Long,
  val lap: Int? = null,
  val kind: String,
  val text: String? = null,
  val sector: Int? = null,
  val status: String? = null,
  val initialOpportunityMs: Long? = null,
  val currentOpportunityMs: Long? = null,
)

@Serializable
data class RecordedSession(
  val schemaVersion: Int = 1,
  val id: String,
  val sourceSessionKey: String,
  val trackId: String,
  val venue: String,
  val provider: String,
  val startedAtEpochMs: Long,
  val endedAtEpochMs: Long,
  val selectedDriverId: String,
  val selectedDriverName: String,
  val kartNumbers: List<String>,
  val laps: List<RecordedLap>,
  val field: List<RecordedDriver>,
  val engineerEvents: List<RecordedEngineerEvent> = emptyList(),
)

interface SessionPersistence {
  suspend fun save(session: RecordedSession)
  suspend fun load(id: String): RecordedSession?
  suspend fun loadAll(): List<RecordedSession>
}

class SessionHistoryStore(context: Context) : SessionPersistence {
  private val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

  override suspend fun save(session: RecordedSession) = withContext(Dispatchers.IO) {
    directory.mkdirs()
    val destination = fileFor(session.id)
    val temporary = File(directory, "${session.id}.tmp")
    temporary.writeText(json.encodeToString(RecordedSession.serializer(), session))
    if (!temporary.renameTo(destination)) {
      destination.writeText(temporary.readText())
      temporary.delete()
    }
    prune()
  }

  override suspend fun load(id: String): RecordedSession? = withContext(Dispatchers.IO) {
    if (!SAFE_ID.matches(id)) return@withContext null
    read(fileFor(id))
  }

  override suspend fun loadAll(): List<RecordedSession> = withContext(Dispatchers.IO) {
    if (!directory.exists()) return@withContext emptyList()
    directory.listFiles { file -> file.extension == "json" }.orEmpty()
      .mapNotNull(::read)
      .filter { it.laps.isNotEmpty() }
      .sortedByDescending(RecordedSession::startedAtEpochMs)
  }

  private fun read(file: File): RecordedSession? =
    runCatching { json.decodeFromString(RecordedSession.serializer(), file.readText()) }.getOrNull()

  private fun fileFor(id: String) = File(directory, "$id.json")

  private fun prune() {
    directory.listFiles { file -> file.extension == "json" }.orEmpty()
      .sortedByDescending(File::lastModified)
      .drop(MAX_SESSIONS)
      .forEach(File::delete)
  }

  private companion object {
    const val DIRECTORY_NAME = "recorded_sessions"
    const val MAX_SESSIONS = 100
    val SAFE_ID = Regex("[A-Za-z0-9-]+")
  }
}

/** Converts normalized live state into durable, provider-independent session evidence. */
internal class SessionRecorder(
  private val store: SessionPersistence,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val mutex = Mutex()
  private var active: RecordedSession? = null
  private var observedSourceSessionKey: String? = null
  private var sourceFirstSeenAtMs: Long? = null
  private var recordingIdentity: String? = null
  private var lastObjectiveSignature: String? = null
  private var lastRelativeSignature: String? = null

  suspend fun observe(state: TimingUiState) = mutex.withLock { observeLocked(state) }

  private suspend fun observeLocked(state: TimingUiState) {
    if (state.isDemo) return
    val sourceKey = state.sessionKey
    if (sourceKey == null || state.status == ConnectionStatus.Disconnected) {
      finishLocked()
      observedSourceSessionKey = null
      sourceFirstSeenAtMs = null
      return
    }
    if (observedSourceSessionKey != sourceKey) {
      finishLocked()
      observedSourceSessionKey = sourceKey
      sourceFirstSeenAtMs = now()
    }
    if (state.rows.any { it.lapHistory.isNotEmpty() } && sourceFirstSeenAtMs == null) sourceFirstSeenAtMs = now()

    val selected = state.rows.firstOrNull { canonicalKartNumber(it.number) == state.selectedKartNumber }
      ?: return
    if (selected.lapHistory.none(LapHistoryEntry::isMeaningful)) return
    val identity = "$sourceKey:${selected.name.ifBlank { selected.id }.trim().lowercase()}"
    if (recordingIdentity != null && recordingIdentity != identity) {
      finishLocked()
      sourceFirstSeenAtMs = now()
    }
    if (active == null) {
      val track = TimingTracks.find(state.selectedTrackId)
      active =
        RecordedSession(
          id = UUID.randomUUID().toString(),
          sourceSessionKey = sourceKey,
          trackId = track?.id ?: state.selectedTrackId.orEmpty(),
          venue = track?.label ?: "Unknown venue",
          provider = providerName(track?.id),
          startedAtEpochMs = sourceFirstSeenAtMs ?: now(),
          endedAtEpochMs = now(),
          selectedDriverId = selected.id,
          selectedDriverName = selected.name.ifBlank { "Driver" },
          kartNumbers = listOfNotNull(canonicalKartNumber(selected.number)),
          laps = emptyList(),
          field = emptyList(),
        )
      recordingIdentity = identity
    }

    val current = requireNotNull(active)
    val recordedLaps =
      selected.lapHistory.filter(LapHistoryEntry::isMeaningful).sortedBy(LapHistoryEntry::lap).map { source ->
        val latest = source.toRecorded(selected, state.rows)
        val previous = current.laps.firstOrNull { it.lap == source.lap }
        latest.copy(
          position = latest.position ?: previous?.position,
          gapAheadMs = latest.gapAheadMs ?: previous?.gapAheadMs,
          gapBehindMs = latest.gapBehindMs ?: previous?.gapBehindMs,
        )
      }
    val field = state.rows.mapNotNull { it.toRecordedDriver() }.sortedBy { it.finalPosition ?: Int.MAX_VALUE }
    val updated =
      current.copy(
        endedAtEpochMs = now(),
        selectedDriverId = selected.id,
        selectedDriverName = selected.name.ifBlank { current.selectedDriverName },
        kartNumbers = (current.kartNumbers + listOfNotNull(canonicalKartNumber(selected.number))).distinct(),
        laps = recordedLaps,
        field = field,
      )
    val evidenceChanged =
      updated.laps != current.laps || updated.field != current.field ||
        updated.kartNumbers != current.kartNumbers || updated.selectedDriverId != current.selectedDriverId
    active = updated
    val coachingChanged = recordStateTransitions(state, selected.lap)
    if (evidenceChanged || coachingChanged) store.save(requireNotNull(active))
  }

  suspend fun recordAnnouncement(sections: List<String>, lap: Int?) = mutex.withLock {
    if (sections.isEmpty()) return@withLock
    appendEvent(
      RecordedEngineerEvent(
        recordedAtEpochMs = now(),
        lap = lap,
        kind = "announcement",
        text = sections.joinToString(". "),
      ),
    )
    store.save(requireNotNull(active))
  }

  suspend fun recordObservation(text: String, lap: Int?, sector: Int?) = mutex.withLock {
    appendEvent(
      RecordedEngineerEvent(
        recordedAtEpochMs = now(),
        lap = lap,
        kind = "observation",
        text = text,
        sector = sector,
      ),
    )
    active?.let { store.save(it) }
  }

  suspend fun finish() = mutex.withLock { finishLocked() }

  private suspend fun finishLocked() {
    active?.takeIf { it.laps.isNotEmpty() }?.let { store.save(it.copy(endedAtEpochMs = now())) }
    active = null
    recordingIdentity = null
    lastObjectiveSignature = null
    lastRelativeSignature = null
  }

  private fun recordStateTransitions(state: TimingUiState, lap: Int?): Boolean {
    var changed = false
    val objective = state.coachingObjective
    val objectiveSignature = "${objective.sector}:${objective.opportunityMs}:${objective.status}"
    if (objective.sector != null && objectiveSignature != lastObjectiveSignature) {
      lastObjectiveSignature = objectiveSignature
      changed = true
      appendEvent(
        RecordedEngineerEvent(
          now(), lap, "objective", sector = objective.sector,
          status = objective.status.name,
          initialOpportunityMs = objective.opportunityMs,
        ),
      )
    }
    val relative = state.relativeOpportunity
    val relativeSignature = "${relative.sector}:${relative.initialOpportunityMs}:${relative.opportunityMs}:${relative.status}"
    if (relative.sector != null && relativeSignature != lastRelativeSignature) {
      lastRelativeSignature = relativeSignature
      changed = true
      appendEvent(
        RecordedEngineerEvent(
          now(), lap, "relative_opportunity", sector = relative.sector,
          status = relative.status.name,
          initialOpportunityMs = relative.initialOpportunityMs,
          currentOpportunityMs = relative.opportunityMs,
        ),
      )
    }
    return changed
  }

  private fun appendEvent(event: RecordedEngineerEvent) {
    val current = active ?: return
    active = current.copy(engineerEvents = (current.engineerEvents + event).takeLast(MAX_ENGINEER_EVENTS))
  }

  private fun LapHistoryEntry.toRecorded(selected: TimingRow, rows: List<TimingRow>): RecordedLap {
    val gaps = calculateAdjacentRaceGaps(rows, selected, lap)
    val isCurrentMeasurement = selected.gapRecordedAtLap == lap || selected.lap == lap
    return RecordedLap(
      lap, lapMs, sector1Ms, sector2Ms, sector3Ms,
      position = selected.position.takeIf { isCurrentMeasurement },
      gapAheadMs = gaps?.ahead?.milliseconds,
      gapBehindMs = gaps?.behind?.milliseconds,
    )
  }

  private fun TimingRow.toRecordedDriver(): RecordedDriver? {
    val canonicalKart = canonicalKartNumber(number) ?: return null
    val validLaps = lapHistory.filter(LapHistoryEntry::isMeaningful).sortedBy(LapHistoryEntry::lap)
    if (validLaps.isEmpty()) return null
    return RecordedDriver(
      id = id,
      name = name,
      kartNumber = canonicalKart,
      finalPosition = position,
      laps = validLaps.map { RecordedLap(it.lap, it.lapMs, it.sector1Ms, it.sector2Ms, it.sector3Ms) },
    )
  }

  private companion object {
    const val MAX_ENGINEER_EVENTS = 250
  }
}

private fun LapHistoryEntry.isMeaningful(): Boolean = lapMs in 5_000L..600_000L

internal fun RecordedDriver.toTimingRow(maxLap: Int? = null): TimingRow {
  val included = laps.filter { maxLap == null || it.lap <= maxLap }.sortedBy(RecordedLap::lap)
  val latest = included.maxByOrNull(RecordedLap::lap)
  val best = included.minByOrNull(RecordedLap::lapTimeMs)
  return TimingRow(
    id = id,
    number = kartNumber,
    name = name,
    position = latest?.position ?: finalPosition,
    lap = latest?.lap,
    lapMs = latest?.lapTimeMs,
    recentCompletedLap = latest?.lap,
    recentCompletedLapMs = latest?.lapTimeMs,
    recentCompletedSector1Ms = latest?.sector1Ms,
    recentCompletedSector2Ms = latest?.sector2Ms,
    recentCompletedSector3Ms = latest?.sector3Ms,
    bestLap = best?.lap,
    bestLapMs = best?.lapTimeMs,
    bestLapSector1Ms = best?.sector1Ms,
    bestLapSector2Ms = best?.sector2Ms,
    bestLapSector3Ms = best?.sector3Ms,
    bestSector1Ms = included.mapNotNull(RecordedLap::sector1Ms).minOrNull(),
    bestSector2Ms = included.mapNotNull(RecordedLap::sector2Ms).minOrNull(),
    bestSector3Ms = included.mapNotNull(RecordedLap::sector3Ms).minOrNull(),
    theoreticalBestMs = listOfNotNull(
      included.mapNotNull(RecordedLap::sector1Ms).minOrNull(),
      included.mapNotNull(RecordedLap::sector2Ms).minOrNull(),
      included.mapNotNull(RecordedLap::sector3Ms).minOrNull(),
    ).takeIf { it.size == 3 }?.sum(),
    lapHistory = included.map { LapHistoryEntry(it.lap, it.lapTimeMs, it.sector1Ms, it.sector2Ms, it.sector3Ms) },
    lapTimeline = included.map { LapTimelineEntry(it.lap, it.lapTimeMs, it.sector1Ms, it.sector2Ms, it.sector3Ms) },
    gapToAheadMs = latest?.gapAheadMs,
    gapRecordedAtLap = latest?.lap.takeIf { latest?.gapAheadMs != null || latest?.gapBehindMs != null },
  )
}

private fun providerName(trackId: String?): String =
  when (trackId) {
    TimingTracks.BuckmorePark.id -> "Alpha Race Hub"
    TimingTracks.DaytonaSandownParkGp.id -> "Clubspeed"
    TimingTracks.TeamSportFarnborough.id -> "SMS-Timing"
    else -> "Unknown provider"
  }
