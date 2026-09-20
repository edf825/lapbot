package com.example.lapbot.ui.main

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.AnnouncementSettings
import com.example.lapbot.data.AnnouncementVoiceGender
import com.example.lapbot.data.LapHistoryEntry
import com.example.lapbot.data.LapTimelineEntry
import com.example.lapbot.data.ReconnectPolicy
import com.example.lapbot.data.TimingRow
import com.example.lapbot.data.TimingServiceRepository
import com.example.lapbot.data.TimingTrack
import com.example.lapbot.data.TimingTracks
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.ToneMetric
import com.example.lapbot.data.ToneSettings
import com.example.lapbot.service.formatSpokenDeltaMagnitude
import com.example.lapbot.data.canonicalKartNumber
import com.example.lapbot.data.canonicalDriverNameFragment
import com.example.lapbot.data.findDriverByNameFragment
import com.example.lapbot.data.metricLapsSince
import com.example.lapbot.theme.LapbotTheme
import kotlin.math.roundToInt
import kotlin.math.absoluteValue

@Composable
fun LiveTimingsScreen(
  onDriverClick: (String) -> Unit,
  onRaceEngineerClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val viewModel = timingViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LiveTimingsScreen(
    state = state,
    onConnect = viewModel::connect,
    onStartDemo = viewModel::startDemo,
    demoEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    onDisconnect = viewModel::disconnect,
    onAutoReconnectChange = viewModel::setAutoReconnect,
    onTailLimitChange = viewModel::setTailLimit,
    onReconnectPolicyChange = viewModel::setReconnectPolicy,
    onDriverClick = onDriverClick,
    onRaceEngineerClick = onRaceEngineerClick,
    modifier = modifier,
  )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LiveTimingsScreen(
  state: TimingUiState,
  onConnect: (String) -> Unit,
  onStartDemo: () -> Unit = {},
  demoEnabled: Boolean = false,
  onDisconnect: () -> Unit,
  onAutoReconnectChange: (Boolean) -> Unit,
  onTailLimitChange: (Int) -> Unit,
  onReconnectPolicyChange: (ReconnectPolicy) -> Unit,
  onDriverClick: (String) -> Unit,
  onRaceEngineerClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedTrackId by rememberSaveable {
    mutableStateOf(state.selectedTrackId ?: if (state.isDemo) TimingTracks.BuckmorePark.id else null)
  }
  val selectedTrack = TimingTracks.find(selectedTrackId)
  var showConfiguration by remember { mutableStateOf(false) }
  var showDebugTools by remember { mutableStateOf(false) }
  var pendingAutoReconnect by remember { mutableStateOf(state.autoReconnect) }
  var pendingTailLimit by remember { mutableFloatStateOf(state.tailLimit.toFloat()) }
  var pendingInitialDelaySeconds by remember { mutableFloatStateOf(state.reconnectPolicy.initialDelayMs / 1_000f) }
  var pendingMaxDelaySeconds by remember { mutableFloatStateOf(state.reconnectPolicy.maxDelayMs / 1_000f) }
  var pendingGiveUpMinutes by remember { mutableFloatStateOf(state.reconnectPolicy.giveUpAfterMs / 60_000f) }
  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ConnectionPanel(
        state = state,
        selectedTrack = selectedTrack,
        onTrackSelected = { track ->
          selectedTrackId = track.id
          onConnect(track.id)
        },
        onRetry = { selectedTrack?.let { onConnect(it.id) } },
        demoEnabled = demoEnabled,
        onDisconnect = {
          selectedTrackId = null
          onDisconnect()
        },
        onShowDebugTools = { showDebugTools = true },
        onConfigure = {
          pendingAutoReconnect = state.autoReconnect
          pendingTailLimit = state.tailLimit.toFloat()
          pendingInitialDelaySeconds = state.reconnectPolicy.initialDelayMs / 1_000f
          pendingMaxDelaySeconds = state.reconnectPolicy.maxDelayMs / 1_000f
          pendingGiveUpMinutes = state.reconnectPolicy.giveUpAfterMs / 60_000f
          showConfiguration = true
        },
      )
      TimingTable(
        rows = state.rows,
        supportsSectors = selectedTrack?.supportsSectors ?: state.supportsSectors,
        onDriverClick = onDriverClick,
        emptyMessage =
          when {
            selectedTrack == null -> "Select a track to load live timing."
            state.status == ConnectionStatus.Connecting -> "Connecting to ${selectedTrack.label}…"
            state.status == ConnectionStatus.Reconnecting -> "Restoring the ${selectedTrack.label} connection…"
            state.status == ConnectionStatus.Connected -> "Connected. Waiting for an active timing session."
            else -> "Connection stopped. Retry when you're ready."
          },
        modifier = Modifier.weight(1f),
      )
    }
    val engineerEnabled = state.status == ConnectionStatus.Connected
    ExtendedFloatingActionButton(
      onClick = { if (engineerEnabled) onRaceEngineerClick() },
      modifier =
        Modifier.align(Alignment.BottomEnd).padding(16.dp)
          .semantics { if (!engineerEnabled) disabled() },
      containerColor =
        if (engineerEnabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
      contentColor =
        if (engineerEnabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
      Text("Race Engineer")
    }
  }
  if (showConfiguration) {
    ConfigurationDialog(
      autoReconnect = pendingAutoReconnect,
      onAutoReconnectChange = { pendingAutoReconnect = it },
      tailLimit = pendingTailLimit,
      onTailLimitChange = { pendingTailLimit = it },
      initialDelaySeconds = pendingInitialDelaySeconds,
      onInitialDelayChange = { pendingInitialDelaySeconds = it },
      maxDelaySeconds = pendingMaxDelaySeconds,
      onMaxDelayChange = { pendingMaxDelaySeconds = it },
      giveUpMinutes = pendingGiveUpMinutes,
      onGiveUpChange = { pendingGiveUpMinutes = it },
      onApply = {
        onAutoReconnectChange(pendingAutoReconnect)
        onTailLimitChange(pendingTailLimit.roundToInt())
        onReconnectPolicyChange(
          ReconnectPolicy(
            initialDelayMs = (pendingInitialDelaySeconds * 1_000).roundToInt().toLong(),
            maxDelayMs = (pendingMaxDelaySeconds * 1_000).roundToInt().toLong(),
            giveUpAfterMs = (pendingGiveUpMinutes * 60_000).roundToInt().toLong(),
          ),
        )
        showConfiguration = false
      },
      onDismiss = { showConfiguration = false },
    )
  }
  if (showDebugTools) {
    DebugToolsDialog(
      canStartReplay = state.status == ConnectionStatus.Disconnected,
      jsonTail = state.jsonTail,
      relativeOpportunity = state.relativeOpportunity,
      onStartReplay = {
        showDebugTools = false
        selectedTrackId = TimingTracks.BuckmorePark.id
        onStartDemo()
      },
      onDismiss = { showDebugTools = false },
    )
  }
}

@Composable
fun DriverScreen(
  driverId: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel = timingViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val driver = state.rows.firstOrNull { it.id == driverId }
  val focusManager = LocalFocusManager.current
  Column(modifier.clearFocusOnTap(focusManager).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeader("Driver", onBack)
    Text(driver?.displayName ?: "Driver unavailable", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    MetricWindowControl(state.metricsSinceLap, viewModel::setMetricsSinceLap)
    DriverComparisonTable(state.rows, driver, state.metricsSinceLap, state.supportsSectors)
    LapHistoryTable(driver?.lapTimeline.orEmpty(), Modifier.weight(1f), supportsSectors = state.supportsSectors)
  }
}

@Composable
fun EngineerSettingsScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel = timingViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val selected = state.rows.firstOrNull { canonicalKartNumber(it.number) == state.selectedKartNumber }
  val focusManager = LocalFocusManager.current
  var showToneConfiguration by remember { mutableStateOf(false) }

  Column(
    modifier.clearFocusOnTap(focusManager).fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    PageHeader("Engineer Settings", onBack, backLabel = "Race Engineer")
    Text(
      selected?.displayName ?: state.selectedKartNumber?.let { "Kart #$it" } ?: "No driver in focus",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
    AnnouncementComparisonControls(
      settings = state.announcementSettings,
      supportsSectors = state.supportsSectors,
      supportsGaps = state.supportsGaps,
      onSettingsChange = viewModel::setAnnouncementSettings,
      onPreview = viewModel::previewAnnouncement,
    )
    MetricWindowControl(state.metricsSinceLap, viewModel::setMetricsSinceLap)
    ToneControls(
      settings = state.toneSettings,
      supportsSectors = state.supportsSectors,
      onSettingsChange = viewModel::setToneSettings,
      onConfigure = { showToneConfiguration = true },
    )
  }
  if (showToneConfiguration) {
    ToneConfigurationDialog(
      settings = state.toneSettings,
      supportsSectors = state.supportsSectors,
      testEnabled = selected != null,
      onApply = {
        viewModel.setToneSettings(it)
        showToneConfiguration = false
      },
      onTest = {
        viewModel.setToneSettings(it)
        viewModel.playTestTones()
      },
      onDismiss = { showToneConfiguration = false },
    )
  }
}

@Composable
fun RaceEngineerScreen(
  onBack: () -> Unit,
  onSettingsClick: () -> Unit,
  onPitlaneModeClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel = timingViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val microphonePermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      viewModel.setListenForCommands(granted)
    }
  val setListenForCommands: (Boolean) -> Unit = { enabled ->
    if (
      !enabled ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) {
      viewModel.setListenForCommands(enabled)
    } else {
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
  RaceEngineerScreen(
    state = state,
    onBack = onBack,
    onSelectedKartNumberChange = viewModel::setSelectedKartNumber,
    onAutoDetectDriverNameChange = viewModel::setAutoDetectDriverName,
    onRadioMessagesChange = viewModel::setCoachEnabled,
    onListenForCommandsChange = setListenForCommands,
    onSettingsClick = onSettingsClick,
    onPitlaneModeClick = onPitlaneModeClick,
    modifier = modifier,
  )
}

@Composable
internal fun RaceEngineerScreen(
  state: TimingUiState,
  onBack: () -> Unit,
  onSelectedKartNumberChange: (String?) -> Unit,
  onAutoDetectDriverNameChange: (String?) -> Unit,
  onRadioMessagesChange: (Boolean) -> Unit,
  onListenForCommandsChange: (Boolean) -> Unit,
  onSettingsClick: () -> Unit,
  onPitlaneModeClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasDriverInFocus = canonicalKartNumber(state.selectedKartNumber) != null
  Column(
    modifier.clearFocusOnTap(LocalFocusManager.current).fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    PageHeader("Race Engineer", onBack)
    DriverFocusControl(state, onSelectedKartNumberChange, onAutoDetectDriverNameChange)
    Row(
      Modifier.fillMaxWidth()
        .toggleable(
          value = state.coachEnabled,
          role = Role.Switch,
          onValueChange = onRadioMessagesChange,
        )
        .padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Engineer Radio Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          if (state.coachEnabled) "Spoken timing and coaching feedback is on" else "Radio messages are off",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Switch(checked = state.coachEnabled, onCheckedChange = null)
    }
    Row(
      Modifier.fillMaxWidth()
        .toggleable(
          value = state.listenForCommands,
          role = Role.Switch,
          onValueChange = onListenForCommandsChange,
        )
        .padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Listen for commands", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          if (state.listenForCommands) "Say “Lapbot, gaps” for an on-demand update" else "Voice commands are off",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Switch(checked = state.listenForCommands, onCheckedChange = null)
    }
    OutlinedButton(onClick = onSettingsClick, modifier = Modifier.fillMaxWidth()) {
      Text("Engineer Settings")
    }
    Button(onClick = onPitlaneModeClick, enabled = hasDriverInFocus, modifier = Modifier.fillMaxWidth()) {
      Text("Pitlane Mode")
    }
    if (!hasDriverInFocus) {
      Text(
        "Pick a driver in focus to enable Pitlane Mode.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
fun PitlaneModeScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val viewModel = timingViewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  PitlaneModeScreen(state = state, onBack = onBack, modifier = modifier)
}

@Composable
internal fun PitlaneModeScreen(
  state: TimingUiState,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selected = state.rows.firstOrNull { canonicalKartNumber(it.number) == state.selectedKartNumber }

  Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeader("Pitlane Mode", onBack, backLabel = "Race Engineer")
    Text(
      "${TimingTracks.find(state.selectedTrackId)?.label ?: "Live timing"} · ${state.status.label}",
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelLarge,
    )
    if (selected == null) {
      Text(
        state.selectedKartNumber?.let { "Waiting for kart #$it to appear in live timing." }
          ?: "Return to Race Engineer and choose a driver in focus.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Text(selected.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      if (state.supportsSectors) RelativeOpportunityCard(state.relativeOpportunity)
      if (state.supportsSectors) CoachingObjectiveCard(state.coachingObjective)
      DriverComparisonTable(state.rows, selected, state.metricsSinceLap, state.supportsSectors)
      LapHistoryTable(selected.lapTimeline, Modifier.weight(1f), supportsSectors = state.supportsSectors)
    }
  }
}

@Composable
private fun DriverFocusControl(
  state: TimingUiState,
  onSelectedKartNumberChange: (String?) -> Unit,
  onAutoDetectDriverNameChange: (String?) -> Unit,
) {
  val drivers = state.rows.filter { canonicalKartNumber(it.number) != null }.sortedByKartNumber()
  val selected = drivers.firstOrNull { canonicalKartNumber(it.number) == state.selectedKartNumber }
  var driverMenuExpanded by remember { mutableStateOf(false) }
  var showKartEntry by remember { mutableStateOf(false) }
  var showDriverNameEntry by remember { mutableStateOf(false) }
  val autoDetected = findDriverByNameFragment(drivers, state.autoDetectDriverName)
  val nameMatchCount =
    state.autoDetectDriverName?.let { fragment ->
      drivers
        .filter { it.name.contains(fragment, ignoreCase = true) }
        .mapNotNull { canonicalKartNumber(it.number) }
        .distinct()
        .size
    } ?: 0

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("Driver in Focus", style = MaterialTheme.typography.labelLarge)
    Box {
      OutlinedButton(onClick = { driverMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
          state.autoDetectDriverName?.let { fragment ->
            autoDetected?.let { "${it.displayName} · auto “$fragment”" }
              ?: "Auto “$fragment” · ${if (nameMatchCount > 1) "ambiguous" else "waiting"}"
          }
            ?: selected?.displayName
            ?: state.selectedKartNumber?.let { "#$it Waiting" }
            ?: "Select list / enter kart / auto-detect name",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      DropdownMenu(expanded = driverMenuExpanded, onDismissRequest = { driverMenuExpanded = false }) {
        DropdownMenuItem(
          text = { Text("Enter kart number...") },
          onClick = {
            driverMenuExpanded = false
            showKartEntry = true
          },
        )
        DropdownMenuItem(
          text = { Text("Auto-detect driver named...") },
          onClick = {
            driverMenuExpanded = false
            showDriverNameEntry = true
          },
        )
        if (drivers.isNotEmpty()) {
          HorizontalDivider()
          DropdownMenuItem(text = { Text("Select from list") }, onClick = {}, enabled = false)
        }
        drivers.forEach { driver ->
          DropdownMenuItem(
            text = { Text(driver.displayName) },
            onClick = {
              onSelectedKartNumberChange(driver.number)
              driverMenuExpanded = false
            },
          )
        }
      }
    }
  }
  if (showKartEntry) {
    KartNumberDialog(
      initialValue = state.selectedKartNumber.orEmpty(),
      onApply = {
        onSelectedKartNumberChange(it)
        showKartEntry = false
      },
      onDismiss = { showKartEntry = false },
    )
  }
  if (showDriverNameEntry) {
    DriverNameDialog(
      initialValue = state.autoDetectDriverName.orEmpty(),
      onApply = {
        onAutoDetectDriverNameChange(it)
        showDriverNameEntry = false
      },
      onDismiss = { showDriverNameEntry = false },
    )
  }
}

@Composable
private fun CoachingObjectiveCard(objective: com.example.lapbot.data.CoachingObjectiveUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("Session objective", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      if (objective.status == com.example.lapbot.data.CoachingObjectiveStatus.CollectingData) {
        Text("Collecting representative sector data", color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        Text("Sector ${objective.sector ?: "—"}")
        objective.opportunityMs?.let {
          Text(
            "Opportunity: ${formatSpokenDeltaMagnitude(it)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          objective.status.objectiveStatusLabel(),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun RelativeOpportunityCard(opportunity: com.example.lapbot.data.RelativeOpportunityUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("Relative opportunity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      if (opportunity.frontRunningSectors.isNotEmpty()) {
        Text(
          "Front-running repeatable pace: ${opportunity.frontRunningSectors.sorted().joinToString { "S$it" }}",
          color = FasterGreen,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (opportunity.sector == null) {
        Text("Collecting credible faster-driver comparisons", color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        Text("Sector ${opportunity.sector} · ${opportunity.status.name.replaceWords()}")
        val references = opportunity.benchmarkKartNumbers.joinToString { "#$it" }
        if (references.isNotEmpty()) Text("Front-running reference: $references", style = MaterialTheme.typography.bodySmall)
        Text(
          "${formatPercentValue(opportunity.relativeDeficitPercent)} off reference · " +
            "${formatPercentValue(opportunity.typicalDeficitPercent)} typical",
          style = MaterialTheme.typography.bodySmall,
        )
        val initial = opportunity.initialOpportunityMs
        val current = opportunity.opportunityMs
        val opportunityLabel =
          if (initial != null && current != null && initial != current) {
            "Current additional loss: ${formatMillis(current)} (from ${formatMillis(initial)})"
          } else {
            "Additional loss indicator: ${formatMillis(current)}"
          }
        Text(
          "$opportunityLabel · ${opportunity.confidence?.name ?: "—"}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

private fun com.example.lapbot.data.CoachingObjectiveStatus.objectiveStatusLabel(): String =
  when (this) {
    com.example.lapbot.data.CoachingObjectiveStatus.CollectingData -> "Collecting data"
    com.example.lapbot.data.CoachingObjectiveStatus.Identified -> "Focus identified"
    com.example.lapbot.data.CoachingObjectiveStatus.Working -> "Working"
    com.example.lapbot.data.CoachingObjectiveStatus.PromisingImprovement -> "Improving"
    com.example.lapbot.data.CoachingObjectiveStatus.ImprovementConfirmed -> "Pace becoming consistent"
    com.example.lapbot.data.CoachingObjectiveStatus.ReadyToReassess -> "Ready to reassess"
  }

@Composable
private fun AnnouncementComparisonControls(
  settings: AnnouncementSettings,
  supportsSectors: Boolean,
  supportsGaps: Boolean,
  onSettingsChange: (AnnouncementSettings) -> Unit,
  onPreview: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Speak last comparison", modifier = Modifier.padding(top = 13.dp), style = MaterialTheme.typography.bodySmall)
      Switch(
        checked = settings.speakLastComparison,
        onCheckedChange = { onSettingsChange(settings.copy(speakLastComparison = it)) },
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Speak best comparison", modifier = Modifier.padding(top = 13.dp), style = MaterialTheme.typography.bodySmall)
      Switch(
        checked = settings.speakBestComparison,
        onCheckedChange = { onSettingsChange(settings.copy(speakBestComparison = it)) },
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Text("Speak gaps", style = MaterialTheme.typography.bodySmall)
        Text(
          if (supportsGaps) "Gap to the positions immediately ahead and behind"
          else "Gap data is unavailable for this timing provider",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = settings.speakGaps,
        enabled = supportsGaps,
        onCheckedChange = { onSettingsChange(settings.copy(speakGaps = it)) },
      )
    }
    Row(
      Modifier.fillMaxWidth().padding(start = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Text("Include kart numbers", style = MaterialTheme.typography.bodySmall)
        Text(
          "Identify the karts occupying the adjacent positions",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = settings.speakGapKartNumbers,
        enabled = supportsGaps && settings.speakGaps,
        onCheckedChange = { onSettingsChange(settings.copy(speakGapKartNumbers = it)) },
      )
    }
    if (supportsSectors) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Text("Speak sector timing", style = MaterialTheme.typography.bodySmall)
        Text(
          "Numeric time starts 1 second after the sound",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = settings.speakSectorDeltas,
        onCheckedChange = { onSettingsChange(settings.copy(speakSectorDeltas = it)) },
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Text(if (supportsSectors) "Sector tones" else "Lap tones", style = MaterialTheme.typography.bodySmall)
        Text(
          if (supportsSectors) "Play a cue before sector and lap calls" else "Play a cue before lap calls",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = settings.sectorTonesEnabled,
        onCheckedChange = { onSettingsChange(settings.copy(sectorTonesEnabled = it)) },
      )
    }
    if (supportsSectors) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f).padding(top = 8.dp)) {
        Text("Speak coaching", style = MaterialTheme.typography.bodySmall)
        Text(
          "Session-aware objectives, trends, and progress",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(
        checked = settings.speakCoaching,
        onCheckedChange = { onSettingsChange(settings.copy(speakCoaching = it)) },
      )
    }
    if (supportsSectors) Text("Coaching detail", style = MaterialTheme.typography.bodySmall)
    if (supportsSectors) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      com.example.lapbot.data.CoachingChattiness.entries.forEach { level ->
        FilterChip(
          selected = settings.coachingChattiness == level,
          onClick = { onSettingsChange(settings.copy(coachingChattiness = level)) },
          label = { Text(level.name) },
        )
      }
    }
    Text("Voice", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      AnnouncementVoiceGender.entries.forEach { gender ->
        FilterChip(
          selected = settings.voiceGender == gender,
          onClick = { onSettingsChange(settings.copy(voiceGender = gender)) },
          label = { Text(gender.name) },
        )
      }
    }
    Text(
      "Voice speed ${(settings.speechRate * 100).roundToInt()}%",
      style = MaterialTheme.typography.bodySmall,
    )
    Slider(
      value = settings.speechRate,
      onValueChange = { onSettingsChange(settings.copy(speechRate = it)) },
      valueRange = 0.8f..1.1f,
    )
    OutlinedButton(onClick = onPreview) { Text("Preview announcement") }
  }
}

@Composable
private fun KartNumberDialog(initialValue: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
  var value by remember(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Select driver by kart") },
    text = {
      OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.length <= 5 && next.all(Char::isDigit)) value = next },
        label = { Text("Kart number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
    },
    confirmButton = {
      TextButton(onClick = { onApply(value) }, enabled = canonicalKartNumber(value) != null) { Text("Select") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun DriverNameDialog(initialValue: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
  var value by remember(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Auto-detect driver") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Lapbot will follow the kart assigned to the single driver whose name contains this phrase.")
        OutlinedTextField(
          value = value,
          onValueChange = { value = it.take(40) },
          label = { Text("Driver name fragment") },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onApply(value) },
        enabled = canonicalDriverNameFragment(value) != null,
      ) { Text("Track driver") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun Modifier.clearFocusOnTap(focusManager: FocusManager): Modifier =
  pointerInput(focusManager) { detectTapGestures { focusManager.clearFocus() } }

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, backLabel: String = "Live Timings") {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = onBack) { Text("‹ $backLabel") }
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun DriverComparisonTable(
  rows: List<TimingRow>,
  selected: TimingRow?,
  sinceLap: Int?,
  supportsSectors: Boolean,
) {
  val horizontalScroll = rememberScrollState()

  Column(Modifier.fillMaxWidth()) {
    if (selected == null) {
      Text("No driver timing is available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      return@Column
    }

    val metricLaps = selected.metricLapsSince(sinceLap)
    val latest = metricLaps.firstOrNull()
    val previous = metricLaps.getOrNull(1)
    val driverBest = metricLaps.minByOrNull(LapHistoryEntry::lapMs)
    val driverBestTime =
      if (sinceLap == null) selected.bestLapMs ?: driverBest?.lapMs
      else driverBest?.lapMs
    val bestSector1 = metricLaps.mapNotNull(LapHistoryEntry::sector1Ms).minOrNull()
    val bestSector2 = metricLaps.mapNotNull(LapHistoryEntry::sector2Ms).minOrNull()
    val bestSector3 = metricLaps.mapNotNull(LapHistoryEntry::sector3Ms).minOrNull()
    val raceBest = rows.filter { it.bestLapMs != null }.minByOrNull { it.bestLapMs ?: Long.MAX_VALUE }
    val bestRecent =
      rows.filter { it.recentCompletedLapMs != null }.minByOrNull { it.recentCompletedLapMs ?: Long.MAX_VALUE }
    val comparisons =
      listOf(
        LapComparison(
          "Most recent",
          selected,
          latest?.lap,
          latest?.lapMs,
          latest?.sector1Ms,
          latest?.sector2Ms,
          latest?.sector3Ms,
        ),
        LapComparison(
          "Previous lap",
          selected,
          previous?.lap,
          previous?.lapMs,
          previous?.sector1Ms,
          previous?.sector2Ms,
          previous?.sector3Ms,
        ),
        LapComparison(
          "Driver best",
          selected,
          if (sinceLap == null) selected.bestLap ?: driverBest?.lap else driverBest?.lap,
          driverBestTime,
          driverBest?.sector1Ms,
          driverBest?.sector2Ms,
          driverBest?.sector3Ms,
        ),
        LapComparison(
          "Theoretical",
          selected,
          null,
          if (bestSector1 != null && bestSector2 != null && bestSector3 != null) bestSector1 + bestSector2 + bestSector3 else null,
          bestSector1,
          bestSector2,
          bestSector3,
        ),
        LapComparison(
          "Race best",
          raceBest,
          raceBest?.bestLap,
          raceBest?.bestLapMs,
          raceBest?.bestLapSector1Ms,
          raceBest?.bestLapSector2Ms,
          raceBest?.bestLapSector3Ms,
        ),
        LapComparison(
          "Best recent",
          bestRecent,
          bestRecent?.recentCompletedLap,
          bestRecent?.recentCompletedLapMs,
          bestRecent?.recentCompletedSector1Ms,
          bestRecent?.recentCompletedSector2Ms,
          bestRecent?.recentCompletedSector3Ms,
        ),
      ).filterNot { !supportsSectors && it.label == "Theoretical" }
    FocusHeader(horizontalScroll, supportsSectors)
    comparisons.forEach { comparison ->
      FocusRow(comparison, latest, horizontalScroll, supportsSectors)
      HorizontalDivider()
    }
  }
}

@Composable
private fun MetricWindowControl(sinceLap: Int?, onSinceLapChange: (Int?) -> Unit) {
  var value by remember(sinceLap) { mutableStateOf(sinceLap?.toString().orEmpty()) }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("Metrics since lap", modifier = Modifier.padding(top = 7.dp), style = MaterialTheme.typography.bodyMedium)
    BasicTextField(
      value = value,
      onValueChange = { next ->
        if (next.length <= 5 && next.all(Char::isDigit)) {
          value = next
          onSinceLapChange(next.toIntOrNull())
        }
      },
      modifier =
        Modifier.width(68.dp)
          .height(34.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 7.dp),
      textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      singleLine = true,
      decorationBox = { innerTextField ->
        Box {
          if (value.isEmpty()) {
            Text("All", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
          }
          innerTextField()
        }
      },
    )
  }
}

@Composable
private fun ToneControls(
  settings: ToneSettings,
  supportsSectors: Boolean,
  onSettingsChange: (ToneSettings) -> Unit,
  onConfigure: () -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Card(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth()
        .toggleable(
          value = settings.enabled,
          role = Role.Switch,
          onValueChange = { onSettingsChange(settings.copy(enabled = it)) },
        )
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Performance tones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          if (settings.enabled) "Play comparison tones after each announced lap" else "Tone playback is off",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Switch(checked = settings.enabled, onCheckedChange = null)
    }
    HorizontalDivider()
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Compare with", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Box {
          OutlinedButton(onClick = { expanded = true }, enabled = settings.enabled) { Text(settings.metric.label) }
          DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ToneMetric.entries.filterNot { !supportsSectors && it == ToneMetric.TheoreticalBest }.forEach { metric ->
              DropdownMenuItem(
                text = { Text(metric.label) },
                onClick = {
                  onSettingsChange(settings.copy(metric = metric))
                  expanded = false
                },
              )
            }
          }
        }
      }
      TextButton(onClick = onConfigure) { Text("Configure") }
    }
  }
}

@Composable
private fun ToneConfigurationDialog(
  settings: ToneSettings,
  supportsSectors: Boolean,
  testEnabled: Boolean,
  onApply: (ToneSettings) -> Unit,
  onTest: (ToneSettings) -> Unit,
  onDismiss: () -> Unit,
) {
  var enabled by remember(settings) { mutableStateOf(settings.enabled) }
  var referenceDuration by remember(settings) { mutableFloatStateOf(settings.referenceDurationMs.toFloat()) }
  var lapDuration by remember(settings) { mutableFloatStateOf(settings.lapDurationMs.toFloat()) }
  var sectorDuration by remember(settings) { mutableFloatStateOf(settings.sectorDurationMs.toFloat()) }
  val pending =
    settings.copy(
      enabled = enabled,
      referenceDurationMs = referenceDuration.roundToDuration(),
      lapDurationMs = lapDuration.roundToDuration(),
      sectorDurationMs = sectorDuration.roundToDuration(),
    )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Tone configuration") },
    text = {
      Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Tone playback", modifier = Modifier.weight(1f))
          Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        DurationSlider("Reference tone", referenceDuration, enabled, { referenceDuration = it })
        DurationSlider("Lap tone", lapDuration, enabled, { lapDuration = it })
        if (supportsSectors) DurationSlider("Sector tones", sectorDuration, enabled, { sectorDuration = it })
        TextButton(onClick = { onTest(pending) }, enabled = testEnabled && enabled) { Text("Test latest lap") }
      }
    },
    confirmButton = { TextButton(onClick = { onApply(pending) }) { Text("Apply") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun DurationSlider(label: String, duration: Float, enabled: Boolean, onDurationChange: (Float) -> Unit) {
  Text("$label: ${duration.roundToDuration()} ms")
  Slider(value = duration, onValueChange = onDurationChange, enabled = enabled, valueRange = 50f..1_000f, steps = 18)
}

private fun Float.roundToDuration(): Int = (roundToInt() / 50 * 50).coerceIn(50, 1_000)

private val ToneMetric.label: String
  get() =
    when (this) {
      ToneMetric.PreviousLap -> "Previous lap"
      ToneMetric.DriverBest -> "Driver best"
      ToneMetric.TheoreticalBest -> "Theoretical"
      ToneMetric.RaceBest -> "Race best"
      ToneMetric.BestRecent -> "Best recent"
    }

@Composable
private fun LapHistoryTable(
  history: List<LapTimelineEntry>,
  modifier: Modifier = Modifier,
  scrollable: Boolean = true,
  supportsSectors: Boolean = true,
) {
  Column(modifier.fillMaxWidth()) {
    Text("Lap history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
      HistoryCell("LAP", 40, true)
      HistoryCell("TIME", 90, true)
      if (supportsSectors) {
        HistoryCell("S1", 70, true)
        HistoryCell("S2", 70, true)
        HistoryCell("S3", 70, true)
      }
    }
    if (history.isEmpty()) {
      Text("No laps available.", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (scrollable) {
      LazyColumn(Modifier.fillMaxSize()) {
        items(history, key = LapTimelineEntry::lap) { lap ->
          Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            HistoryCell(lap.lap.toString(), 40)
            HistoryCell(formatMillis(lap.lapMs), 90)
            if (supportsSectors) {
              HistoryCell(formatMillis(lap.sector1Ms), 70)
              HistoryCell(formatMillis(lap.sector2Ms), 70)
              HistoryCell(formatMillis(lap.sector3Ms), 70)
            }
          }
          HorizontalDivider()
        }
      }
    } else {
      history.forEach { lap ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
          HistoryCell(lap.lap.toString(), 40)
          HistoryCell(formatMillis(lap.lapMs), 90)
          if (supportsSectors) {
            HistoryCell(formatMillis(lap.sector1Ms), 70)
            HistoryCell(formatMillis(lap.sector2Ms), 70)
            HistoryCell(formatMillis(lap.sector3Ms), 70)
          }
        }
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun RowScope.HistoryCell(value: String, width: Int, header: Boolean = false) {
  Text(
    text = value,
    modifier = Modifier.width(width.dp).padding(horizontal = 3.dp),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    maxLines = 1,
  )
}

@Composable
private fun FocusHeader(horizontalScroll: ScrollState, supportsSectors: Boolean) {
  Row(Modifier.horizontalScroll(horizontalScroll).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
    FocusCell("METRIC", 100, true)
    FocusCell("TIME / DELTA", 130, true)
    if (supportsSectors) {
      FocusCell("S1 / DELTA", 105, true)
      FocusCell("S2 / DELTA", 105, true)
      FocusCell("S3 / DELTA", 105, true)
    }
    FocusCell("DRIVER / LAP", 180, true)
  }
}

@Composable
private fun FocusRow(
  comparison: LapComparison,
  latest: LapHistoryEntry?,
  horizontalScroll: ScrollState,
  supportsSectors: Boolean,
) {
  Row(Modifier.horizontalScroll(horizontalScroll).padding(vertical = 4.dp)) {
    FocusCell(comparison.label, 100)
    TimeDeltaCell(comparison.timeMs, latest?.lapMs, 130)
    if (supportsSectors) {
      TimeDeltaCell(comparison.sector1Ms, latest?.sector1Ms, 105, compact = true)
      TimeDeltaCell(comparison.sector2Ms, latest?.sector2Ms, 105, compact = true)
      TimeDeltaCell(comparison.sector3Ms, latest?.sector3Ms, 105, compact = true)
    }
    FocusCell(
      comparison.driver?.let { "${it.name} / ${comparison.lap?.let { lap -> "L$lap" } ?: "mixed"}" }.orDash(),
      180,
    )
  }
}

@Composable
private fun RowScope.TimeDeltaCell(
  metricMs: Long?,
  latestMs: Long?,
  width: Int,
  compact: Boolean = false,
) {
  val delta = if (metricMs != null && latestMs != null) latestMs - metricMs else null
  Row(Modifier.width(width.dp).padding(horizontal = 4.dp)) {
    Text(formatMillis(metricMs), style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
    Spacer(Modifier.width(if (compact) 3.dp else 5.dp))
    Text(
      formatDelta(metricMs, latestMs),
      color =
        when {
          delta == null || delta == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
          delta < 0 -> FasterGreen
          else -> MaterialTheme.colorScheme.error
        },
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
private fun RowScope.FocusCell(value: String, width: Int, header: Boolean = false) {
  Text(
    text = value,
    modifier = Modifier.width(width.dp).padding(horizontal = 4.dp),
    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

private data class LapComparison(
  val label: String,
  val driver: TimingRow?,
  val lap: Int?,
  val timeMs: Long?,
  val sector1Ms: Long?,
  val sector2Ms: Long?,
  val sector3Ms: Long?,
)

private val FasterGreen = Color(0xFF2EAD62)

private val TimingRow.displayName: String
  get() = if (number.isBlank()) name else "#$number $name"

private fun List<TimingRow>.sortedByKartNumber(): List<TimingRow> =
  sortedWith(compareBy<TimingRow> { it.number.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number }.thenBy { it.name })

@Composable
private fun timingViewModel(): MainScreenViewModel {
  val context = LocalContext.current
  return viewModel { MainScreenViewModel(TimingServiceRepository(context)) }
}

@Composable
private fun ConnectionPanel(
  state: TimingUiState,
  selectedTrack: TimingTrack?,
  onTrackSelected: (TimingTrack) -> Unit,
  onRetry: () -> Unit,
  demoEnabled: Boolean,
  onDisconnect: () -> Unit,
  onShowDebugTools: () -> Unit,
  onConfigure: () -> Unit,
) {
  var trackMenuExpanded by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Lapbot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      if (demoEnabled) TextButton(onClick = onShowDebugTools) { Text("Debug tools") }
    }
    ConnectionControl(
      state = state,
      selectedTrack = selectedTrack,
      trackMenuExpanded = trackMenuExpanded,
      onTrackMenuExpandedChange = { trackMenuExpanded = it },
      onTrackSelected = { track ->
        if (selectedTrack?.id != track.id) onTrackSelected(track)
      },
      onRetry = onRetry,
      onDisconnect = onDisconnect,
      onConfigure = onConfigure,
    )
    if (state.isDemo) {
      Text(
        "Session 837888 · kart #5 John Reeves · replayed at 2× speed",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    state.error?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
  }
}

@Composable
private fun ConnectionControl(
  state: TimingUiState,
  selectedTrack: TimingTrack?,
  trackMenuExpanded: Boolean,
  onTrackMenuExpandedChange: (Boolean) -> Unit,
  onTrackSelected: (TimingTrack) -> Unit,
  onRetry: () -> Unit,
  onDisconnect: () -> Unit,
  onConfigure: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    if (selectedTrack == null) {
      Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Select a track", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "Lapbot will connect automatically and show the active timing session.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        TrackMenu(
          expanded = trackMenuExpanded,
          buttonLabel = "Choose track",
          onExpandedChange = onTrackMenuExpandedChange,
          onTrackSelected = onTrackSelected,
          usePrimaryButton = true,
        )
      }
    } else {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(selectedTrack.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text(
            if (state.isDemo) "Demo replay" else state.status.label,
            color = if (state.status == ConnectionStatus.Connected) MaterialTheme.colorScheme.primary else Color.Unspecified,
            style = MaterialTheme.typography.labelLarge,
          )
          Text(
            state.connectionDescription(selectedTrack.label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        when (state.status) {
          ConnectionStatus.Disconnected -> Button(onClick = onRetry) { Text("Retry") }
          ConnectionStatus.Connected -> OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
          ConnectionStatus.Connecting -> Button(onClick = {}, enabled = false) { Text("Connecting…") }
          ConnectionStatus.Reconnecting -> Button(onClick = {}, enabled = false) { Text("Retrying…") }
        }
      }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      if (selectedTrack != null) {
        TrackMenu(
          expanded = trackMenuExpanded,
          buttonLabel = "Change track",
          onExpandedChange = onTrackMenuExpandedChange,
          onTrackSelected = onTrackSelected,
        )
      } else {
        Spacer(Modifier.width(1.dp))
      }
      TextButton(onClick = onConfigure) { Text("Advanced settings") }
    }
  }
}

@Composable
private fun TrackMenu(
  expanded: Boolean,
  buttonLabel: String,
  onExpandedChange: (Boolean) -> Unit,
  onTrackSelected: (TimingTrack) -> Unit,
  usePrimaryButton: Boolean = false,
) {
  Box {
    if (usePrimaryButton) {
      Button(onClick = { onExpandedChange(true) }) { Text(buttonLabel) }
    } else {
      TextButton(onClick = { onExpandedChange(true) }) { Text(buttonLabel) }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
      TimingTracks.All.forEach { track ->
        DropdownMenuItem(
          text = { Text(track.label) },
          onClick = {
            onExpandedChange(false)
            onTrackSelected(track)
          },
        )
      }
    }
  }
}

private fun TimingUiState.connectionDescription(trackLabel: String): String =
    when {
      isDemo -> "Replaying a recorded timing session"
      status == ConnectionStatus.Disconnected -> "The connection stopped before a session became active"
      status == ConnectionStatus.Connecting -> "Finding the active session"
      status == ConnectionStatus.Connected -> "Active session connected"
      else -> "Trying to restore the $trackLabel connection"
    }

@Composable
private fun DebugToolsDialog(
  canStartReplay: Boolean,
  jsonTail: List<String>,
  relativeOpportunity: com.example.lapbot.data.RelativeOpportunityUiState,
  onStartReplay: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Debug tools") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Replay a recorded Buckmore Park session using synthesized sector timings.")
        if (!canStartReplay) {
          Text(
            "Disconnect from Live Timings before starting a replay.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        RelativeOpportunityDebug(relativeOpportunity)
        JsonTail(jsonTail, Modifier.fillMaxWidth().height(160.dp))
      }
    },
    confirmButton = {
      TextButton(onClick = onStartReplay, enabled = canStartReplay) { Text("Replay session 837888") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}

@Composable
private fun RelativeOpportunityDebug(opportunity: com.example.lapbot.data.RelativeOpportunityUiState) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("Relative coaching analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (opportunity.sector == null) {
      Text("No established external opportunity", style = MaterialTheme.typography.labelSmall)
    } else {
      Text(
        "S${opportunity.sector}: driver=${formatMillis(opportunity.driverPaceMs)}, " +
          "benchmark=${formatMillis(opportunity.benchmarkPaceMs)}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        "deficit=${formatPercentValue(opportunity.relativeDeficitPercent)}, " +
          "typical=${formatPercentValue(opportunity.typicalDeficitPercent)}, " +
          "excess=${formatPercentagePoints(opportunity.excessDeficitPercentagePoints)}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(
        "opportunity=${formatMillis(opportunity.initialOpportunityMs)}→${formatMillis(opportunity.opportunityMs)}, samples=" +
          "${opportunity.driverSampleCount}/${opportunity.benchmarkSampleCount}, " +
          "reference=${opportunity.benchmarkKartNumbers.joinToString()}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun TimingTable(
  rows: List<TimingRow>,
  supportsSectors: Boolean,
  onDriverClick: (String) -> Unit,
  emptyMessage: String = "Select a track to load live timing.",
  modifier: Modifier = Modifier,
) {
  val horizontalScroll = rememberScrollState()
  Column(modifier) {
    Text("Current timing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Row(
      Modifier.fillMaxWidth().horizontalScroll(horizontalScroll).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp),
    ) {
      TableCell("POS", 36, true)
      TableCell("NO", 40, true)
      TableCell("DRIVER", 130, true)
      TableCell("LAP", 40, true)
      TableCell("LAP TIME", 84, true)
      if (supportsSectors) {
        TableCell("S1", 72, true)
        TableCell("S2", 72, true)
        TableCell("S3", 72, true)
      }
    }
    if (rows.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(16.dp)) {
        Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = TimingRow::id) { row ->
          val previousLap = row.lapTimeline.getOrNull(1)
          Row(Modifier.clickable { onDriverClick(row.id) }.horizontalScroll(horizontalScroll).padding(vertical = 4.dp)) {
            TableCell(row.position?.toString().orDash(), 36)
            TableCell(row.number.orDash(), 40)
            TableCell(row.name.orDash(), 130)
            TableCell(row.lap?.toString().orDash(), 40)
            RaceTimeCell(row.lapMs, previousLap?.lapMs, 84)
            if (supportsSectors) {
              RaceTimeCell(row.sector1Ms, previousLap?.sector1Ms, 72)
              RaceTimeCell(row.sector2Ms, previousLap?.sector2Ms, 72)
              RaceTimeCell(row.sector3Ms, previousLap?.sector3Ms, 72)
            }
          }
          HorizontalDivider()
        }
      }
    }
  }
}

@Composable
private fun RowScope.RaceTimeCell(currentMs: Long?, previousMs: Long?, width: Int) {
  TableCell(
    value = formatMillis(currentMs ?: previousMs),
    width = width,
    color =
      if (currentMs == null && previousMs != null) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
      else Color.Unspecified,
  )
}

@Composable
private fun RowScope.TableCell(value: String, width: Int, header: Boolean = false, color: Color = Color.Unspecified) {
  Text(
    text = value,
    modifier = Modifier.width(width.dp).padding(horizontal = 3.dp),
    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    color = color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun JsonTail(
  records: List<String>,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(records.size) {
    if (records.isNotEmpty()) listState.scrollToItem(records.lastIndex)
  }
  Column(modifier) {
    Text("JSON stream tail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
      if (records.isEmpty()) {
        Text("Live updates will appear here. The initial snapshot is omitted.", style = MaterialTheme.typography.bodySmall)
      } else {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
          items(records) { record ->
            Text(record, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }
}

@Composable
private fun ConfigurationDialog(
  autoReconnect: Boolean,
  onAutoReconnectChange: (Boolean) -> Unit,
  tailLimit: Float,
  onTailLimitChange: (Float) -> Unit,
  initialDelaySeconds: Float,
  onInitialDelayChange: (Float) -> Unit,
  maxDelaySeconds: Float,
  onMaxDelayChange: (Float) -> Unit,
  giveUpMinutes: Float,
  onGiveUpChange: (Float) -> Unit,
  onApply: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Advanced settings") },
    text = {
      Column {
        Row(
          Modifier.fillMaxWidth()
            .toggleable(
              value = autoReconnect,
              role = Role.Switch,
              onValueChange = onAutoReconnectChange,
            )
            .padding(vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1f)) {
            Text("Auto-reconnect")
            Text(
              "Retry automatically if the timing connection drops",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(checked = autoReconnect, onCheckedChange = null)
        }
        Text("JSON tail: latest ${tailLimit.roundToInt()} messages")
        Slider(
          value = tailLimit,
          onValueChange = onTailLimitChange,
          valueRange = 5f..100f,
          steps = 18,
        )
        Text("Reconnect initially after ${formatSeconds(initialDelaySeconds)}")
        Slider(
          value = initialDelaySeconds,
          onValueChange = onInitialDelayChange,
          valueRange = 0.5f..10f,
          steps = 18,
        )
        Text("Maximum reconnect delay: ${maxDelaySeconds.roundToInt()} seconds")
        Slider(
          value = maxDelaySeconds,
          onValueChange = onMaxDelayChange,
          valueRange = 5f..60f,
          steps = 10,
        )
        Text("Give up after ${giveUpMinutes.roundToInt()} minutes")
        Slider(
          value = giveUpMinutes,
          onValueChange = onGiveUpChange,
          valueRange = 1f..30f,
          steps = 28,
        )
      }
    },
    confirmButton = { TextButton(onClick = onApply) { Text("Apply") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun formatSeconds(seconds: Float): String =
  if (seconds % 1f == 0f) "${seconds.roundToInt()} seconds" else "${seconds}s"

private fun formatPercentValue(value: Double?): String = value?.let { "%.1f%%".format(it) } ?: "—"

private fun formatPercentagePoints(value: Double?): String = value?.let { "%+.1fpp".format(it) } ?: "—"

private fun String.replaceWords(): String =
  replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar(Char::uppercase)

private val ConnectionStatus.label: String
  get() =
    when (this) {
      ConnectionStatus.Disconnected -> "Disconnected"
      ConnectionStatus.Connecting -> "Connecting..."
      ConnectionStatus.Connected -> "Live"
      ConnectionStatus.Reconnecting -> "Reconnecting..."
    }

private fun String?.orDash(): String = if (isNullOrBlank()) "--" else this

private fun formatMillis(milliseconds: Long?): String {
  if (milliseconds == null) return "--"
  val minutes = milliseconds / 60_000
  val seconds = (milliseconds % 60_000) / 1_000
  val millis = milliseconds % 1_000
  return if (minutes > 0) "%d:%02d.%03d".format(minutes, seconds, millis) else "%d.%03d".format(seconds, millis)
}

private fun formatDelta(metricMs: Long?, latestMs: Long?): String {
  if (metricMs == null || latestMs == null) return "--"
  val delta = latestMs - metricMs
  val sign = if (delta >= 0) "+" else "-"
  return sign + formatMillis(delta.absoluteValue)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 820)
@Composable
private fun LiveTimingsScreenPreview() {
  LapbotTheme {
    LiveTimingsScreen(
      state =
        TimingUiState(
          status = ConnectionStatus.Connected,
          rows =
            listOf(
              TimingRow("1", "9", "Jameel Sesay", 1, 13, 22_425, 16_360, 16_025, 54_868),
              TimingRow("2", "7", "Jamele McIntosh", 2, 12, 23_176, 18_257, 18_091, 59_524),
            ),
          jsonTail = listOf("{\"type\":\"lap_update\",\"number\":\"7\",\"lap\":12,\"lap_ms\":59524}"),
        ),
      onConnect = {},
      onDisconnect = {},
      onAutoReconnectChange = {},
      onTailLimitChange = {},
      onReconnectPolicyChange = {},
      onDriverClick = {},
      onRaceEngineerClick = {},
    )
  }
}
