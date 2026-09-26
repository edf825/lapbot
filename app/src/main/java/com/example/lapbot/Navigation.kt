package com.example.lapbot

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.lapbot.ui.main.LiveTimingsScreen
import com.example.lapbot.ui.main.EngineerSettingsScreen
import com.example.lapbot.ui.main.DriverScreen
import com.example.lapbot.ui.main.PitlaneModeScreen
import com.example.lapbot.ui.main.RaceEngineerScreen
import com.example.lapbot.ui.sessions.SessionDebriefScreen
import com.example.lapbot.ui.sessions.SessionsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(LiveTimings)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<LiveTimings> {
          LiveTimingsScreen(
            onDriverClick = { driverId -> backStack.add(Driver(driverId)) },
            onRaceEngineerClick = { backStack.add(RaceEngineer) },
            onSessionsClick = { backStack.add(Sessions) },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<Driver> { key ->
          DriverScreen(
            driverId = key.driverId,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<EngineerSettings> {
          EngineerSettingsScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<RaceEngineer> {
          RaceEngineerScreen(
            onBack = { backStack.removeLastOrNull() },
            onSettingsClick = { backStack.add(EngineerSettings) },
            onPitlaneModeClick = { backStack.add(PitlaneMode) },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<PitlaneMode> {
          PitlaneModeScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<Sessions> {
          SessionsScreen(
            onBack = { backStack.removeLastOrNull() },
            onSessionClick = { sessionId -> backStack.add(SessionDebrief(sessionId)) },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
        entry<SessionDebrief> { key ->
          SessionDebriefScreen(
            sessionId = key.sessionId,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding().padding(12.dp),
          )
        }
      },
  )
}
