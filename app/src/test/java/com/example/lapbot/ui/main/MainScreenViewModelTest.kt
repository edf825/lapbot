package com.example.lapbot.ui.main

import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.AnnouncementSettings
import com.example.lapbot.data.TimingRepository
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.ReconnectPolicy
import com.example.lapbot.data.ToneSettings
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun connect_isForwardedToRepository() {
    val repository = FakeTimingRepository()
    val viewModel = MainScreenViewModel(repository)

    viewModel.connect()

    assertEquals(ConnectionStatus.Connecting, viewModel.uiState.value.status)
  }

  @Test
  fun autoReconnect_isOnByDefault() {
    val viewModel = MainScreenViewModel(FakeTimingRepository())

    assertEquals(true, viewModel.uiState.value.autoReconnect)
  }

  @Test
  fun previewAnnouncement_isForwardedToRepository() {
    val repository = FakeTimingRepository()
    val viewModel = MainScreenViewModel(repository)

    viewModel.previewAnnouncement()

    assertEquals(1, repository.previewCount)
  }
}

private class FakeTimingRepository : TimingRepository {
  override val state = MutableStateFlow(TimingUiState())
  var previewCount = 0

  override fun connect(trackId: String) {
    state.value = state.value.copy(status = ConnectionStatus.Connecting, selectedTrackId = trackId)
  }

  override fun startDemo() {
    state.value = state.value.copy(status = ConnectionStatus.Connected, isDemo = true)
  }

  override fun disconnect() {
    state.value = state.value.copy(status = ConnectionStatus.Disconnected)
  }

  override fun setAutoReconnect(enabled: Boolean) {
    state.value = state.value.copy(autoReconnect = enabled)
  }

  override fun setTailLimit(limit: Int) {
    state.value = state.value.copy(tailLimit = limit, jsonTail = state.value.jsonTail.takeLast(limit))
  }

  override fun setReconnectPolicy(policy: ReconnectPolicy) {
    state.value = state.value.copy(reconnectPolicy = policy)
  }

  override fun setSelectedKartNumber(kartNumber: String?) {
    state.value = state.value.copy(selectedKartNumber = kartNumber)
  }

  override fun setMetricsSinceLap(lap: Int?) {
    state.value = state.value.copy(metricsSinceLap = lap)
  }

  override fun setCoachEnabled(enabled: Boolean) {
    state.value = state.value.copy(coachEnabled = enabled)
  }

  override fun setAnnouncementSettings(settings: AnnouncementSettings) {
    state.value = state.value.copy(announcementSettings = settings)
  }

  override fun previewAnnouncement() {
    previewCount += 1
  }

  override fun setToneSettings(settings: ToneSettings) {
    state.value = state.value.copy(toneSettings = settings)
  }

  override fun playTestTones() = Unit

  override fun close() = Unit
}
