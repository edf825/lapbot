package com.example.lapbot.data

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class SessionRecorderTest {
  @Test
  fun `recorded session schema round trips through durable json`() {
    val original =
      RecordedSession(
        id = "session-1",
        sourceSessionKey = "race-1",
        trackId = "buckmore",
        venue = "Buckmore Park",
        provider = "Alpha Race Hub",
        startedAtEpochMs = 1,
        endedAtEpochMs = 2,
        selectedDriverId = "driver-12",
        selectedDriverName = "Jonny Reeves",
        kartNumbers = listOf("12", "27"),
        laps = listOf(RecordedLap(1, 52_300, 20_000, 15_000, 17_300, 4, 1_200, 330)),
        field = emptyList(),
        engineerEvents = listOf(RecordedEngineerEvent(2, 1, "announcement", "Great lap")),
      )

    val encoded = Json.encodeToString(RecordedSession.serializer(), original)

    assertEquals(original, Json.decodeFromString(RecordedSession.serializer(), encoded))
  }

  @Test
  fun `connection attempts and sessions without focused meaningful laps are not stored`() = runTest {
    val persistence = FakeSessionPersistence()
    val recorder = SessionRecorder(persistence) { 1_000L }

    recorder.observe(TimingUiState(status = ConnectionStatus.Connected, sessionKey = "race-1"))
    recorder.observe(
      TimingUiState(
        status = ConnectionStatus.Connected,
        sessionKey = "race-1",
        selectedKartNumber = "12",
        rows =
          listOf(
            TimingRow(
              id = "12",
              number = "12",
              name = "Reeves",
              lapHistory = listOf(LapHistoryEntry(1, 1)),
            ),
          ),
      ),
    )
    recorder.finish()

    assertTrue(persistence.sessions.isEmpty())
  }

  @Test
  fun `first completed lap starts an automatic provider independent recording`() = runTest {
    val persistence = FakeSessionPersistence()
    var clock = 1_000L
    val recorder = SessionRecorder(persistence) { clock++ }
    val focused =
      TimingRow(
        id = "driver-12",
        number = "12",
        name = "Jonny Reeves",
        position = 4,
        lap = 1,
        lapHistory = listOf(LapHistoryEntry(1, 52_300, 20_000, 15_000, 17_300)),
      )

    recorder.observe(
      TimingUiState(
        status = ConnectionStatus.Connected,
        sessionKey = "race-1",
        selectedTrackId = TimingTracks.BuckmorePark.id,
        selectedKartNumber = "12",
        rows = listOf(focused),
      ),
    )
    recorder.recordAnnouncement(listOf("Great lap", "52 point 30"), lap = 1)
    recorder.finish()

    val session = persistence.sessions.values.single()
    assertEquals("Buckmore Park", session.venue)
    assertEquals("Alpha Race Hub", session.provider)
    assertEquals("Jonny Reeves", session.selectedDriverName)
    assertEquals(listOf("12"), session.kartNumbers)
    assertEquals(52_300L, session.laps.single().lapTimeMs)
    assertEquals("Great lap. 52 point 30", session.engineerEvents.single().text)
  }

  @Test
  fun `same named driver remains in one recording when kart assignment changes`() = runTest {
    val persistence = FakeSessionPersistence()
    val recorder = SessionRecorder(persistence) { 1_000L }

    recorder.observe(stateForKart("12", lap = 1))
    recorder.observe(stateForKart("27", lap = 2))
    recorder.finish()

    val session = persistence.sessions.values.single()
    assertEquals(listOf("12", "27"), session.kartNumbers)
    assertEquals(listOf(1, 2), session.laps.map(RecordedLap::lap))
  }

  private fun stateForKart(kart: String, lap: Int): TimingUiState =
    TimingUiState(
      status = ConnectionStatus.Connected,
      sessionKey = "race-1",
      selectedTrackId = TimingTracks.BuckmorePark.id,
      selectedKartNumber = kart,
      rows =
        listOf(
          TimingRow(
            id = "driver-$kart",
            number = kart,
            name = "Jonny Reeves",
            lap = lap,
            lapHistory = (1..lap).map { LapHistoryEntry(it, 52_000L + it) },
          ),
        ),
    )
}

private class FakeSessionPersistence : SessionPersistence {
  val sessions = linkedMapOf<String, RecordedSession>()

  override suspend fun save(session: RecordedSession) {
    sessions[session.id] = session
  }

  override suspend fun load(id: String): RecordedSession? = sessions[id]

  override suspend fun loadAll(): List<RecordedSession> = sessions.values.toList()
}
