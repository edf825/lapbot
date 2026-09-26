package com.example.lapbot.data

import android.util.Log
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Client for TeamSport's SMS-Timing full-snapshot WebSocket feed. */
class TeamSportTimingRepository(
  private val track: TimingTrack = TimingTracks.TeamSportFarnborough,
  private val webSocketUrl: String = FARNBOROUGH_WEB_SOCKET_URL,
  private val resourceKey: String = FARNBOROUGH_RESOURCE_KEY,
) : TimingRepository {
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
  private val client =
    OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .pingInterval(WEB_SOCKET_PING_SECONDS, TimeUnit.SECONDS)
      .build()
  private val accumulator = TeamSportTimingAccumulator(resourceKey)
  private val reconnectBackoff = ReconnectBackoff()
  private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TimingUiState())
  override val state: kotlinx.coroutines.flow.StateFlow<TimingUiState> = mutableState

  private var socket: WebSocket? = null
  private var generation = 0
  private var wantsConnection = false
  private var reconnectJob: Job? = null
  private var subscriptionTimeoutJob: Job? = null
  private var backoffResetJob: Job? = null

  override fun connect(trackId: String) {
    scope.launch {
      if (mutableState.value.status != ConnectionStatus.Disconnected) return@launch
      wantsConnection = true
      reconnectBackoff.reset()
      generation += 1
      open(generation, reconnecting = false)
    }
  }

  override fun startDemo() = Unit

  override fun disconnect() {
    scope.launch {
      wantsConnection = false
      generation += 1
      reconnectJob?.cancel()
      subscriptionTimeoutJob?.cancel()
      backoffResetJob?.cancel()
      reconnectBackoff.reset()
      socket?.close(1000, "Disconnected")
      socket = null
      mutableState.value =
        mutableState.value.copy(status = ConnectionStatus.Disconnected, error = null)
    }
  }

  override fun setAutoReconnect(enabled: Boolean) {
    scope.launch {
      mutableState.value = mutableState.value.copy(autoReconnect = enabled)
      if (!enabled && mutableState.value.status == ConnectionStatus.Reconnecting) {
        wantsConnection = false
        generation += 1
        reconnectJob?.cancel()
        mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected)
      }
    }
  }

  override fun setTailLimit(limit: Int) {
    scope.launch {
      val bounded = limit.coerceIn(MIN_TAIL_RECORDS, MAX_TAIL_RECORDS)
      mutableState.value =
        mutableState.value.copy(
          tailLimit = bounded,
          jsonTail = mutableState.value.jsonTail.takeLast(bounded),
        )
    }
  }

  override fun setReconnectPolicy(policy: ReconnectPolicy) {
    scope.launch {
      val bounded = policy.boundedForTeamSport()
      reconnectBackoff.updatePolicy(bounded)
      mutableState.value = mutableState.value.copy(reconnectPolicy = bounded)
    }
  }

  override fun setSelectedKartNumber(kartNumber: String?) {
    scope.launch {
      mutableState.value =
        mutableState.value.copy(selectedKartNumber = canonicalKartNumber(kartNumber), autoDetectDriverName = null)
    }
  }

  override fun setAutoDetectDriverName(nameFragment: String?) {
    scope.launch {
      val canonical = canonicalDriverNameFragment(nameFragment)
      mutableState.value =
        mutableState.value.copy(
          autoDetectDriverName = canonical,
          selectedKartNumber = findDriverByNameFragment(mutableState.value.rows, canonical)?.number?.let(::canonicalKartNumber),
        )
    }
  }

  override fun setMetricsSinceLap(lap: Int?) {
    scope.launch { mutableState.value = mutableState.value.copy(metricsSinceLap = lap) }
  }

  override fun setCoachEnabled(enabled: Boolean) {
    scope.launch { mutableState.value = mutableState.value.copy(coachEnabled = enabled) }
  }

  override fun setListenForCommands(enabled: Boolean) {
    scope.launch { mutableState.value = mutableState.value.copy(listenForCommands = enabled) }
  }

  override fun setAnnouncementSettings(settings: AnnouncementSettings) {
    scope.launch { mutableState.value = mutableState.value.copy(announcementSettings = settings) }
  }

  override fun previewAnnouncement() = Unit

  override fun setToneSettings(settings: ToneSettings) {
    scope.launch { mutableState.value = mutableState.value.copy(toneSettings = settings) }
  }

  override fun playTestTones() = Unit

  override fun close() {
    wantsConnection = false
    reconnectJob?.cancel()
    subscriptionTimeoutJob?.cancel()
    backoffResetJob?.cancel()
    socket?.cancel()
    client.dispatcher.executorService.shutdown()
    scope.cancel()
  }

  private fun open(activeGeneration: Int, reconnecting: Boolean) {
    mutableState.value =
      mutableState.value.copy(
        status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
        selectedTrackId = track.id,
        supportsSectors = track.supportsSectors,
        supportsGaps = track.supportsGaps,
        rows = if (reconnecting) mutableState.value.rows else emptyList(),
        jsonTail = if (reconnecting) mutableState.value.jsonTail else emptyList(),
        error = null,
      )
    Log.i(TAG, "Opening SMS-Timing stream for $resourceKey")
    socket =
      client.newWebSocket(
        Request.Builder().url(webSocketUrl).build(),
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
              if (activeGeneration != generation || !wantsConnection) {
                webSocket.close(1000, "Superseded")
                return@launch
              }
              if (!webSocket.send(subscriptionCommand(resourceKey))) {
                fail(activeGeneration, IOException("SMS-Timing subscription could not be sent"))
                return@launch
              }
              startSubscriptionTimeout(activeGeneration)
            }
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(activeGeneration, text) }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
              fail(activeGeneration, IOException("SMS-Timing stream closed ($code): $reason"))
            }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { fail(activeGeneration, t) }
          }
        },
      )
  }

  private fun startSubscriptionTimeout(activeGeneration: Int) {
    subscriptionTimeoutJob?.cancel()
    subscriptionTimeoutJob =
      scope.launch {
        delay(SUBSCRIPTION_TIMEOUT_MS)
        if (activeGeneration == generation && mutableState.value.status != ConnectionStatus.Connected) {
          fail(activeGeneration, IOException("SMS-Timing did not return a timing snapshot"))
        }
      }
  }

  private fun handleMessage(activeGeneration: Int, text: String) {
    if (activeGeneration != generation || !wantsConnection) return
    try {
      val snapshot = json.parseToJsonElement(text) as? JsonObject
        ?: throw IOException("SMS-Timing returned a non-object message")
      val rows = accumulator.apply(snapshot)
      mutableState.value =
        mutableState.value.copy(
          rows = rows,
          sessionKey = accumulator.sessionKey,
          jsonTail = (mutableState.value.jsonTail + text).takeLast(mutableState.value.tailLimit),
        )
      markConnected()
    } catch (error: Exception) {
      socket?.cancel()
      fail(activeGeneration, error)
    }
  }

  private fun markConnected() {
    subscriptionTimeoutJob?.cancel()
    if (!wantsConnection || mutableState.value.status == ConnectionStatus.Connected) return
    Log.i(TAG, "SMS-Timing subscription connected")
    mutableState.value = mutableState.value.copy(status = ConnectionStatus.Connected, error = null)
    backoffResetJob?.cancel()
    backoffResetJob =
      scope.launch {
        delay(BACKOFF_RESET_AFTER_MS)
        if (mutableState.value.status == ConnectionStatus.Connected) reconnectBackoff.reset()
      }
  }

  private fun fail(activeGeneration: Int, error: Throwable) {
    if (activeGeneration != generation) return
    generation += 1
    Log.w(TAG, "SMS-Timing stream failure", error)
    subscriptionTimeoutJob?.cancel()
    backoffResetJob?.cancel()
    socket?.cancel()
    socket = null
    val message = error.message ?: error.javaClass.simpleName
    if (wantsConnection && mutableState.value.autoReconnect) {
      val delayMs = reconnectBackoff.nextDelay()
      if (delayMs == null) {
        giveUpReconnecting()
        return
      }
      mutableState.value =
        mutableState.value.copy(
          status = ConnectionStatus.Reconnecting,
          error = "$message Retrying in ${formatDelay(delayMs)}.",
        )
      val reconnectGeneration = generation
      reconnectJob?.cancel()
      reconnectJob =
        scope.launch {
          delay(delayMs)
          if (!wantsConnection || reconnectGeneration != generation) return@launch
          if (reconnectBackoff.expired()) {
            giveUpReconnecting()
            return@launch
          }
          open(reconnectGeneration, reconnecting = true)
        }
    } else {
      wantsConnection = false
      mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected, error = message)
    }
  }

  private fun giveUpReconnecting() {
    wantsConnection = false
    mutableState.value =
      mutableState.value.copy(
        status = ConnectionStatus.Disconnected,
        error = "Reconnect limit reached. Tap Connect to try again.",
      )
  }

  private fun formatDelay(delayMs: Long): String =
    if (delayMs < 1_000) "${delayMs}ms" else "${delayMs / 1_000}s"

  internal companion object {
    const val FARNBOROUGH_WEB_SOCKET_URL = "wss://webserver3.sms-timing.com:10015/"
    const val FARNBOROUGH_RESOURCE_KEY = "260831@teamsportfarnborough"
    const val LEICESTER_WEB_SOCKET_URL = "wss://webserver4.sms-timing.com:10015/"
    const val LEICESTER_RESOURCE_KEY = "19476@teamsportleicester"
    const val TAG = "LapbotTeamSport"
    const val MIN_TAIL_RECORDS = 5
    const val MAX_TAIL_RECORDS = 100
    const val WEB_SOCKET_PING_SECONDS = 30L
    const val SUBSCRIPTION_TIMEOUT_MS = 15_000L
    const val BACKOFF_RESET_AFTER_MS = 30_000L

    fun forTrack(track: TimingTrack): TeamSportTimingRepository =
      when (track.id) {
        TimingTracks.TeamSportLeicester.id ->
          TeamSportTimingRepository(
            track = TimingTracks.TeamSportLeicester,
            webSocketUrl = LEICESTER_WEB_SOCKET_URL,
            resourceKey = LEICESTER_RESOURCE_KEY,
          )
        else ->
          TeamSportTimingRepository(
            track = TimingTracks.TeamSportFarnborough,
            webSocketUrl = FARNBOROUGH_WEB_SOCKET_URL,
            resourceKey = FARNBOROUGH_RESOURCE_KEY,
          )
      }
  }
}

internal fun subscriptionCommand(resourceKey: String): String = "START $resourceKey"

internal class TeamSportTimingAccumulator(
  private val resourceKey: String,
) {
  private var sessionIdentity: String? = null
  private val histories = mutableMapOf<String, MutableMap<Int, Long>>()
  private val lastLapCounts = mutableMapOf<String, Int>()

  val sessionKey: String?
    get() = sessionIdentity?.let { "sms-timing:$resourceKey:$it" }

  fun apply(snapshot: JsonObject): List<TimingRow> {
    val drivers = snapshot["D"] as? JsonArray
    val nextIdentity = snapshot.sessionIdentity()
    if (nextIdentity == null && drivers == null) {
      clearSession()
      return emptyList()
    }
    if (nextIdentity != sessionIdentity) {
      histories.clear()
      lastLapCounts.clear()
      sessionIdentity = nextIdentity
    }

    return drivers.orEmpty().mapNotNull { element ->
      val driver = element as? JsonObject ?: return@mapNotNull null
      val kart = driver.text("K").orEmpty()
      val timingId = driver.text("D") ?: kart.takeIf(String::isNotBlank)?.let { "kart:$it" }
        ?: return@mapNotNull null
      val lap = driver.int("L")?.takeIf { it >= 0 }
      val previousLap = lastLapCounts[timingId]
      if (lap != null && previousLap != null && lap < previousLap) histories.remove(timingId)
      if (lap != null) lastLapCounts[timingId] = lap

      val lastLapMs = driver.long("T")?.takeIf { it > 0 }
      val history = histories.getOrPut(timingId) { mutableMapOf() }
      if (lap != null && lap > 0 && lastLapMs != null) history[lap] = lastLapMs
      val lapHistory =
        history.entries.sortedByDescending(Map.Entry<Int, Long>::key).map { (number, timeMs) ->
          LapHistoryEntry(lap = number, lapMs = timeMs)
        }
      val bestLapMs = driver.long("B")?.takeIf { it > 0 }
      val position = driver.int("P")
      val gapToLeaderMs = if (position == 1) 0L else parseSmsTimingGapMs(driver.text("G"))

      TimingRow(
        id = "sms-timing:$resourceKey:$timingId",
        number = kart,
        name = driver.text("N").orEmpty(),
        position = position,
        gapToLeaderMs = gapToLeaderMs,
        gapRecordedAtLap = lap?.takeIf { gapToLeaderMs != null },
        lap = lap,
        lapMs = lastLapMs,
        recentCompletedLap = lap?.takeIf { it > 0 && lastLapMs != null },
        recentCompletedLapMs = lastLapMs,
        bestLap = lapHistory.firstOrNull { it.lapMs == bestLapMs }?.lap,
        bestLapMs = bestLapMs,
        lapHistory = lapHistory,
        lapTimeline = lapHistory.map { LapTimelineEntry(lap = it.lap, lapMs = it.lapMs) },
      )
    }.sortedWith(compareBy<TimingRow> { it.position ?: Int.MAX_VALUE }.thenBy { it.number })
  }

  private fun clearSession() {
    sessionIdentity = null
    histories.clear()
    lastLapCounts.clear()
  }

  private fun JsonObject.sessionIdentity(): String? {
    val start = long("T")?.takeIf { it > 0 }
    if (start != null) return start.toString()
    return text("N")?.takeIf(String::isNotBlank)?.let { "pending:$it" }
  }
}

internal fun parseSmsTimingGapMs(value: String?): Long? {
  val text = value?.trim()?.removePrefix("+")?.takeIf(String::isNotEmpty) ?: return null
  return runCatching {
    BigDecimal(text).multiply(BigDecimal(1_000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
  }.getOrNull()
}

private fun ReconnectPolicy.boundedForTeamSport(): ReconnectPolicy {
  val initial = initialDelayMs.coerceIn(500, 10_000)
  val maximum = maxDelayMs.coerceIn(initial, 60_000)
  return copy(
    initialDelayMs = initial,
    maxDelayMs = maximum,
    giveUpAfterMs = giveUpAfterMs.coerceIn(60_000, 30 * 60_000),
  )
}

private fun JsonObject.text(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeUnless { it == "null" }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
