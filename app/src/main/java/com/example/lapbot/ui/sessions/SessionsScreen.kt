package com.example.lapbot.ui.sessions

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lapbot.data.RecordedLap
import com.example.lapbot.data.RecordedSession
import com.example.lapbot.data.SessionHistoryStore
import com.example.lapbot.data.TimingServiceRepository
import com.example.lapbot.service.SessionDebrief
import com.example.lapbot.service.SessionDebriefAnalyzer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(onBack: () -> Unit, onSessionClick: (String) -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val sessions by produceState<List<RecordedSession>?>(initialValue = null, context) {
    value = SessionHistoryStore(context).loadAll()
  }
  Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    SessionsHeader("Sessions", onBack)
    when {
      sessions == null -> Text("Loading recorded sessions…", color = MaterialTheme.colorScheme.onSurfaceVariant)
      sessions.orEmpty().isEmpty() ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("No recorded sessions yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              "Lapbot records automatically once a focused driver has completed a meaningful lap.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      else ->
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(sessions.orEmpty(), key = RecordedSession::id) { session ->
            SessionCard(session, onClick = { onSessionClick(session.id) })
          }
        }
    }
  }
}

@Composable
fun SessionDebriefScreen(sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val loadResult by produceState<Pair<Boolean, RecordedSession?>>(initialValue = false to null, sessionId) {
    value = true to SessionHistoryStore(context).load(sessionId)
  }
  val isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
  val recorded = loadResult.second
  if (recorded == null) {
    Column(modifier.fillMaxSize()) {
      SessionsHeader("Session Debrief", onBack)
      Text(
        if (loadResult.first) "This recorded session is unavailable." else "Loading session…",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }
  val debrief = SessionDebriefAnalyzer().analyze(recorded)
  LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item { SessionsHeader("Session Debrief", onBack) }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(recorded.venue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
          "${formatDate(recorded.startedAtEpochMs)} · ${recorded.selectedDriverName} · ${formatKarts(recorded.kartNumbers)} · ${recorded.laps.size} laps",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    item { DebriefSummary(debrief) }
    item { PaceProgression(recorded.laps, debrief) }
    item { EngineerDebrief(debrief) }
    if (isDebug) {
      item {
        Button(
          onClick = { TimingServiceRepository(context).replayRecordedSession(recorded.id) },
          modifier = Modifier.fillMaxWidth(),
        ) { Text("Replay through Race Engineer") }
      }
    }
    item { Text("Lap detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
    items(recorded.laps.sortedBy(RecordedLap::lap), key = RecordedLap::lap) { lap ->
      LapDebriefRow(lap, debrief)
    }
  }
}

@Composable
private fun SessionCard(session: RecordedSession, onClick: () -> Unit) {
  val best = session.laps.minOfOrNull(RecordedLap::lapTimeMs)
  val finalPosition =
    session.laps.lastOrNull { it.position != null }?.position
      ?: session.field.firstOrNull { it.id == session.selectedDriverId }?.finalPosition
  Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(session.venue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(formatDate(session.startedAtEpochMs), style = MaterialTheme.typography.labelMedium)
      }
      Text(
        "${session.selectedDriverName} · ${formatKarts(session.kartNumbers)}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        listOfNotNull(
          best?.let { "Best ${formatTime(it)}" },
          finalPosition?.let { "P$it final observed" },
          "${session.laps.size} laps",
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun DebriefSummary(debrief: SessionDebrief) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("How did I perform?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      SummaryRow("Best lap", formatTime(debrief.bestLapMs), "Repeatable pace", formatTime(debrief.repeatablePaceMs))
      SummaryRow("Consistency", debrief.consistencyMs?.let { "±${formatDelta(it)}" } ?: "Not established", "Optimal lap", formatTime(debrief.theoreticalLapMs))
      SummaryRow(
        "Best to optimal",
        debrief.theoreticalGapMs?.let(::formatDelta) ?: "Not available",
        "Front-runner pace",
        debrief.relativePacePercent?.let { String.format(Locale.UK, "%.1f%% off", it) } ?: "Not established",
      )
      if (debrief.benchmarkKartNumbers.isNotEmpty()) {
        Text(
          "Credible repeatable reference: ${debrief.benchmarkKartNumbers.joinToString { "#$it" }}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun SummaryRow(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    SummaryMetric(firstLabel, firstValue, Modifier.weight(1f))
    SummaryMetric(secondLabel, secondValue, Modifier.weight(1f))
  }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp)) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun PaceProgression(laps: List<RecordedLap>, debrief: SessionDebrief) {
  val ordered = laps.sortedBy(RecordedLap::lap)
  val representative = ordered.filterNot { it.lap in debrief.outlierLaps }
  val min = representative.minOfOrNull(RecordedLap::lapTimeMs) ?: return
  val max = representative.maxOfOrNull(RecordedLap::lapTimeMs)?.coerceAtLeast(min + 1) ?: return
  val primary = MaterialTheme.colorScheme.primary
  val pb = Color(0xFF2EAD62)
  val outlier = MaterialTheme.colorScheme.error
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Pace progression", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        if (ordered.isEmpty()) return@Canvas
        val step = if (ordered.size == 1) size.width else size.width / (ordered.size - 1)
        fun point(index: Int, lap: RecordedLap): Offset {
          val bounded = lap.lapTimeMs.coerceIn(min, max)
          val y = 12f + ((bounded - min).toFloat() / (max - min).toFloat()) * (size.height - 24f)
          return Offset(index * step, y)
        }
        ordered.zipWithNext().forEachIndexed { index, pair ->
          drawLine(primary.copy(alpha = 0.45f), point(index, pair.first), point(index + 1, pair.second), strokeWidth = 4f)
        }
        ordered.forEachIndexed { index, lap ->
          val color = when (lap.lap) { in debrief.outlierLaps -> outlier; in debrief.personalBestLaps -> pb; else -> primary }
          drawCircle(color, radius = 7f, center = point(index, lap))
        }
      }
      Text("Green: personal best · Red: excluded slow/outlier lap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun EngineerDebrief(debrief: SessionDebrief) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Race Engineer debrief", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text("Where was I losing time?", style = MaterialTheme.typography.labelLarge)
      Text(debrief.conclusions.firstOrNull().orEmpty())
      Text("Did I improve?", style = MaterialTheme.typography.labelLarge)
      debrief.conclusions.drop(1).forEach { Text(it) }
      Text(debrief.objectiveSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun LapDebriefRow(lap: RecordedLap, debrief: SessionDebrief) {
  val marker = when (lap.lap) { in debrief.outlierLaps -> "Slow/outlier"; in debrief.personalBestLaps -> "Personal best"; else -> null }
  Card(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Column {
        Text("Lap ${lap.lap}", fontWeight = FontWeight.SemiBold)
        Text(
          listOfNotNull(
            lap.sector1Ms?.let { "S1 ${formatTime(it)}" },
            lap.sector2Ms?.let { "S2 ${formatTime(it)}" },
            lap.sector3Ms?.let { "S3 ${formatTime(it)}" },
            lap.position?.let { "P$it" },
            lap.gapAheadMs?.let { "Ahead ${formatDelta(it)}" },
            lap.gapBehindMs?.let { "Behind ${formatDelta(it)}" },
          ).joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(formatTime(lap.lapTimeMs), fontWeight = FontWeight.Bold)
        marker?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (lap.lap in debrief.outlierLaps) MaterialTheme.colorScheme.error else Color(0xFF2EAD62)) }
      }
    }
  }
}

@Composable
private fun SessionsHeader(title: String, onBack: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = onBack) { Text("‹ Live Timings") }
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
  }
}

private fun formatTime(milliseconds: Long?): String =
  milliseconds?.let { String.format(Locale.UK, "%d.%03d", it / 1_000, it % 1_000) } ?: "Not established"

private fun formatDelta(milliseconds: Long): String =
  if (milliseconds < 1_000) String.format(Locale.UK, "0.%03d", milliseconds)
  else String.format(Locale.UK, "%d.%03d", milliseconds / 1_000, milliseconds % 1_000)

private fun formatDate(epochMs: Long): String =
  SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(epochMs))

private fun formatKarts(kartNumbers: List<String>): String =
  if (kartNumbers.isEmpty()) "Kart unknown" else kartNumbers.joinToString(prefix = "Kart #", separator = " → #")
