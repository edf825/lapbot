package com.example.lapbot.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.LapTimelineEntry
import com.example.lapbot.data.TimingRow
import junit.framework.TestCase.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()
  private var connectCount = 0
  private var connectedTrackId: String? = null
  private var engineerSettingsCount = 0
  private var destination by mutableStateOf(TestDestination.LiveTimings)
  private var uiState by mutableStateOf(TimingUiState())

  @Before
  fun setup() {
    connectCount = 0
    connectedTrackId = null
    engineerSettingsCount = 0
    destination = TestDestination.LiveTimings
    uiState = TimingUiState()
    composeTestRule.setContent {
      when (destination) {
        TestDestination.LiveTimings ->
          LiveTimingsScreen(
            state = uiState,
            demoEnabled = true,
            onConnect = { trackId ->
              connectCount += 1
              connectedTrackId = trackId
            },
            onDisconnect = {},
            onAutoReconnectChange = {},
            onTailLimitChange = {},
            onReconnectPolicyChange = {},
            onDriverClick = {},
            onRaceEngineerClick = { destination = TestDestination.RaceEngineer },
          )
        TestDestination.RaceEngineer ->
          RaceEngineerScreen(
            state = uiState,
            onBack = { destination = TestDestination.LiveTimings },
            onSelectedKartNumberChange = { uiState = uiState.copy(selectedKartNumber = it) },
            onRadioMessagesChange = { uiState = uiState.copy(coachEnabled = it) },
            onSettingsClick = { engineerSettingsCount += 1 },
            onPitlaneModeClick = { destination = TestDestination.PitlaneMode },
          )
        TestDestination.PitlaneMode ->
          PitlaneModeScreen(
            state = uiState,
            onBack = { destination = TestDestination.RaceEngineer },
          )
      }
    }
  }

  @Test
  fun disconnectedControlsAreShown() {
    composeTestRule.onNodeWithText("Select a track").assertExists()
    composeTestRule.onNodeWithText("Choose track").assertExists()
    composeTestRule.onAllNodesWithText("Connect").assertCountEquals(0)
    composeTestRule.onNodeWithText("Advanced settings").assertExists()
    composeTestRule.onAllNodesWithText("Auto-reconnect").assertCountEquals(0)
    composeTestRule.onNodeWithText("Race Engineer").assertIsNotEnabled()
    composeTestRule.onNodeWithText("Current timing").assertExists()
    composeTestRule.onAllNodesWithText("JSON stream tail").assertCountEquals(0)
    composeTestRule.onAllNodesWithText("Replay session 837888").assertCountEquals(0)
  }

  @Test
  fun selectingTrackStartsConnectionAutomatically() {
    composeTestRule.onNodeWithText("Choose track").performClick()
    composeTestRule.onNodeWithText("Buckmore Park").performClick()

    composeTestRule.runOnIdle { assertEquals(1, connectCount) }
    composeTestRule.onNodeWithText("Retry").assertExists()
  }

  @Test
  fun daytonaGpCircuitIsAvailableAndSelectsItsProvider() {
    composeTestRule.onNodeWithText("Choose track").performClick()
    composeTestRule.onNodeWithText("Daytona Sandown Park GP Circuit").performClick()

    composeTestRule.runOnIdle {
      assertEquals(1, connectCount)
      assertEquals("daytona-sandown-gp", connectedTrackId)
    }
  }

  @Test
  fun engineerFabOpensScreenAndDriverSelectionEnablesPitlaneMode() {
    composeTestRule.runOnIdle {
      uiState =
        TimingUiState(
          status = ConnectionStatus.Connected,
          selectedTrackId = "daytona-sandown-gp",
          supportsSectors = false,
          rows =
            listOf(
              TimingRow(
                id = "20",
                number = "20",
                name = "Elias Davey",
                lap = 2,
                lapMs = 54_919,
                lapHistory = listOf(LapHistoryEntry(2, 54_919), LapHistoryEntry(1, 55_100)),
                lapTimeline = listOf(LapTimelineEntry(2, 54_919), LapTimelineEntry(1, 55_100)),
              ),
            ),
        )
    }

    composeTestRule.onNodeWithText("Race Engineer").assertIsEnabled().performClick()
    composeTestRule.onNodeWithText("Driver in Focus").assertExists()
    composeTestRule.onNodeWithText("Engineer Radio Messages").assertExists()
    composeTestRule.onNodeWithText("Engineer Settings").assertExists()
    composeTestRule.onNodeWithText("Pitlane Mode").assertIsNotEnabled()
    composeTestRule.onAllNodesWithText("Lap history").assertCountEquals(0)

    composeTestRule.onNodeWithText("Enter kart number / pick from list").performClick()
    composeTestRule.onNodeWithText("#20 Elias Davey").performClick()
    composeTestRule.onNodeWithText("Pitlane Mode").assertIsEnabled().performClick()

    composeTestRule.onNodeWithText("Lap history").assertExists()
    composeTestRule.onNodeWithText("‹ Race Engineer").assertExists()
  }

  @Test
  fun engineerSettingsIsAChildOfRaceEngineerScreen() {
    composeTestRule.runOnIdle { uiState = TimingUiState(status = ConnectionStatus.Connected) }
    composeTestRule.onNodeWithText("Race Engineer").performClick()

    composeTestRule.onNodeWithText("Engineer Settings").performClick()

    composeTestRule.runOnIdle { assertEquals(1, engineerSettingsCount) }
  }

  @Test
  fun engineerRadioMessagesDefaultOnAndCanBeDisabled() {
    composeTestRule.runOnIdle { uiState = TimingUiState(status = ConnectionStatus.Connected) }
    composeTestRule.onNodeWithText("Race Engineer").performClick()

    composeTestRule.onNodeWithText("Spoken timing and coaching feedback is on").assertExists()
    composeTestRule.onNodeWithText("Engineer Radio Messages").performClick()

    composeTestRule.runOnIdle { assertEquals(false, uiState.coachEnabled) }
    composeTestRule.onNodeWithText("Radio messages are off").assertExists()
  }

  @Test
  fun replayIsContainedInDebugToolsDialog() {
    composeTestRule.onNodeWithText("Debug tools").performClick()

    composeTestRule.onNodeWithText("Replay session 837888").assertExists()
    composeTestRule.onNodeWithText("JSON stream tail").assertExists()
  }

  @Test
  fun autoReconnectIsContainedInAdvancedSettings() {
    composeTestRule.onNodeWithText("Advanced settings").performClick()

    composeTestRule.onNodeWithText("Auto-reconnect").assertExists()
  }

  private enum class TestDestination {
    LiveTimings,
    RaceEngineer,
    PitlaneMode,
  }
}
