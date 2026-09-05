package com.example.lapbot.data

import android.util.Log
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Client for Clubspeed's legacy SignalR 1.x live-score endpoint. */
class ClubspeedTimingRepository(
  private val track: TimingTrack = TimingTracks.DaytonaSandownParkGp,
  private val trackNumber: Int = DAYTONA_GP_TRACK_NUMBER,
  private val baseUrl: String = DAYTONA_BASE_URL,
) : TimingRepository {
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
  private val client =
    OkHttpClient.Builder()
      .readTimeout(LONG_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // Clubspeed runs a SignalR 1.x server whose concurrent /connect and /send
      // requests can leave the HTTP/2 control stream waiting indefinitely even
      // though timing updates are already flowing. The original browser client
      // uses HTTP/1.1, so keep this legacy transport on the same protocol.
      .protocols(listOf(Protocol.HTTP_1_1))
      .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
      }
      .build()
  private val accumulator = ClubspeedTimingAccumulator()
  private val reconnectBackoff = ReconnectBackoff()
  private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TimingUiState())
  override val state: kotlinx.coroutines.flow.StateFlow<TimingUiState> = mutableState

  private var generation = 0
  private var wantsConnection = false
  private var clientId = ""
  private var messageId: String? = null
  private var groups = ""
  private var invocationId = 0
  private var pollJob: Job? = null
  private var invocationJob: Job? = null
  private var reconnectJob: Job? = null
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
      pollJob?.cancel()
      invocationJob?.cancel()
      reconnectJob?.cancel()
      backoffResetJob?.cancel()
      client.dispatcher.cancelAll()
      reconnectBackoff.reset()
      mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected, error = null)
    }
  }

  override fun setAutoReconnect(enabled: Boolean) {
    scope.launch {
      mutableState.value = mutableState.value.copy(autoReconnect = enabled)
      if (!enabled && mutableState.value.status == ConnectionStatus.Reconnecting) {
        wantsConnection = false
        generation += 1
        pollJob?.cancel()
        reconnectJob?.cancel()
        mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected)
      }
    }
  }

  override fun setTailLimit(limit: Int) {
    scope.launch {
      val bounded = limit.coerceIn(MIN_TAIL_RECORDS, MAX_TAIL_RECORDS)
      mutableState.value =
        mutableState.value.copy(tailLimit = bounded, jsonTail = mutableState.value.jsonTail.takeLast(bounded))
    }
  }

  override fun setReconnectPolicy(policy: ReconnectPolicy) {
    scope.launch {
      reconnectBackoff.updatePolicy(policy)
      mutableState.value = mutableState.value.copy(reconnectPolicy = policy)
    }
  }

  override fun setSelectedKartNumber(kartNumber: String?) {
    scope.launch { mutableState.value = mutableState.value.copy(selectedKartNumber = canonicalKartNumber(kartNumber)) }
  }

  override fun setMetricsSinceLap(lap: Int?) {
    scope.launch { mutableState.value = mutableState.value.copy(metricsSinceLap = lap) }
  }

  override fun setCoachEnabled(enabled: Boolean) {
    scope.launch { mutableState.value = mutableState.value.copy(coachEnabled = enabled) }
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
    pollJob?.cancel()
    invocationJob?.cancel()
    reconnectJob?.cancel()
    client.dispatcher.cancelAll()
    client.dispatcher.executorService.shutdown()
    scope.cancel()
  }

  private suspend fun open(activeGeneration: Int, reconnecting: Boolean) {
    mutableState.value =
      mutableState.value.copy(
        status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
        selectedTrackId = track.id,
        supportsSectors = track.supportsSectors,
        rows = if (reconnecting) mutableState.value.rows else emptyList(),
        jsonTail = if (reconnecting) mutableState.value.jsonTail else emptyList(),
        error = null,
      )
    try {
      val negotiation = post("$baseUrl/signalr/negotiate", ByteArray(0).toRequestBody())
      clientId = negotiation.jsonObject.requiredText("ClientId")
      messageId = null
      groups = ""
      invocationId = 0
      pollJob?.cancel()
      pollJob = scope.launch { poll(activeGeneration) }
      // This mirrors the legacy JavaScript client, which starts hub calls shortly after
      // opening the first long-poll request rather than waiting for that request to end.
      delay(HUB_START_DELAY_MS)
      invocationJob?.cancel()
      invocationJob =
        scope.launch {
          try {
            requestScoreboard(activeGeneration)
          } catch (error: Exception) {
            if (activeGeneration != generation || !wantsConnection) return@launch
            // A legacy hub invocation may be processed without its response ever
            // completing. Once refreshGrid data has arrived, that control-response
            // timeout is irrelevant and must not tear down the healthy poll stream.
            if (shouldIgnoreClubspeedInvocationFailure(mutableState.value.status, error)) {
              Log.i(TAG, "Ignoring timed-out hub response after live data arrived")
            } else {
              fail(activeGeneration, error)
            }
          }
        }
    } catch (error: Exception) {
      fail(activeGeneration, error)
    }
  }

  private suspend fun poll(activeGeneration: Int) {
    var first = true
    try {
      while (wantsConnection && activeGeneration == generation) {
        val body =
          FormBody.Builder()
            .add("clientId", clientId)
            .add("messageId", messageId.orEmpty())
            .add("connectionData", CONNECTION_DATA)
            .add("transport", "longPolling")
            .add("groups", groups)
            .build()
        val endpoint = if (first) "$baseUrl/signalr/connect" else "$baseUrl/signalr"
        try {
          val envelope = post(endpoint, body)
          first = false
          handleEnvelope(envelope, activeGeneration)
        } catch (error: SocketTimeoutException) {
          // A quiet legacy long poll reaching the client read timeout is normal.
          // Re-open only that poll with the same cursor instead of reconnecting
          // and renegotiating the entire timing session.
          Log.i(TAG, "Long poll timed out while idle; reopening")
        }
      }
    } catch (error: Exception) {
      if (wantsConnection && activeGeneration == generation) fail(activeGeneration, error)
    }
  }

  private suspend fun requestScoreboard(activeGeneration: Int) {
    val callId = (invocationId++).toString()
    val invocation =
      JsonObject(
        mapOf(
          "hub" to JsonPrimitive(HUB_NAME),
          "action" to JsonPrimitive("getDataByTrack"),
          "data" to JsonArray(listOf(JsonPrimitive(trackNumber))),
          "state" to JsonObject(emptyMap()),
          "id" to JsonPrimitive(callId.toInt()),
        ),
      )
    val body =
      FormBody.Builder()
        .add("data", invocation.toString())
        .add("transport", "longPolling")
        .add("clientId", clientId)
        .build()
    handleEnvelope(post("$baseUrl/signalr/send", body), activeGeneration)
  }

  private fun handleEnvelope(element: JsonElement, activeGeneration: Int) {
    if (activeGeneration != generation) return
    val envelope = element as? JsonObject ?: return
    envelope.text("MessageId")?.let { messageId = it }
    (envelope["TransportData"] as? JsonObject)?.get("Groups")?.let { value ->
      groups =
        when (value) {
          is JsonPrimitive -> value.content
          is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
          else -> groups
        }
    }
    (envelope["Result"] as? JsonObject)?.let(::applyScoreboard)
    (envelope["Messages"] as? JsonArray).orEmpty().forEach { messageElement ->
      val message = messageElement as? JsonObject ?: return@forEach
      if (message.text("Hub") != HUB_NAME || message.text("Method") != "refreshGrid") return@forEach
      val scoreboard = (message["Args"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@forEach
      applyScoreboard(scoreboard)
    }
    envelope.text("Error")?.let { throw IOException("Clubspeed SignalR error: $it") }
  }

  private fun applyScoreboard(scoreboard: JsonObject) {
    val rows = accumulator.apply(scoreboard)
    mutableState.value =
      mutableState.value.copy(
        rows = rows,
        jsonTail = (mutableState.value.jsonTail + scoreboard.toString()).takeLast(mutableState.value.tailLimit),
      )
    markConnected()
  }

  private fun markConnected() {
    if (!wantsConnection || mutableState.value.status == ConnectionStatus.Connected) return
    mutableState.value = mutableState.value.copy(status = ConnectionStatus.Connected, error = null)
    backoffResetJob?.cancel()
    backoffResetJob =
      scope.launch {
        delay(BACKOFF_RESET_AFTER_MS)
        if (mutableState.value.status == ConnectionStatus.Connected) reconnectBackoff.reset()
      }
  }

  private suspend fun post(url: String, body: okhttp3.RequestBody): JsonElement {
    val response = client.newCall(Request.Builder().url(url).post(body).build()).await()
    response.use {
      if (!it.isSuccessful) throw IOException("HTTP ${it.code} ${it.message}")
      val text = it.body?.string().orEmpty()
      if (text.isBlank()) return JsonObject(emptyMap())
      return json.parseToJsonElement(text)
    }
  }

  private fun fail(activeGeneration: Int, error: Throwable) {
    if (activeGeneration != generation) return
    // Invalidate both concurrent legacy SignalR requests before cancelling them,
    // so their cancellation callbacks cannot schedule duplicate reconnects.
    generation += 1
    Log.w(TAG, "Clubspeed stream failure", error)
    pollJob?.cancel()
    invocationJob?.cancel()
    client.dispatcher.cancelAll()
    backoffResetJob?.cancel()
    val message = error.message ?: error.javaClass.simpleName
    if (wantsConnection && mutableState.value.autoReconnect) {
      val delayMs = reconnectBackoff.nextDelay()
      if (delayMs == null) {
        wantsConnection = false
        mutableState.value =
          mutableState.value.copy(status = ConnectionStatus.Disconnected, error = "Reconnect limit reached. Tap Connect to try again.")
        return
      }
      mutableState.value =
        mutableState.value.copy(status = ConnectionStatus.Reconnecting, error = "$message Retrying shortly.")
      reconnectJob?.cancel()
      reconnectJob =
        scope.launch {
          delay(delayMs)
          if (!wantsConnection) return@launch
          open(generation, reconnecting = true)
        }
    } else {
      wantsConnection = false
      mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected, error = message)
    }
  }

  private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
          }
        },
      )
    }

  private companion object {
    const val TAG = "LapbotClubspeed"
    const val DAYTONA_BASE_URL = "https://daytonasp.clubspeedtiming.com/sp_center"
    const val DAYTONA_GP_TRACK_NUMBER = 3
    const val HUB_NAME = "SP_Center.ScoreBoardHub"
    const val CONNECTION_DATA = "[{\"name\":\"SP_Center.ScoreBoardHub\",\"methods\":[\"refreshGrid\"]}]"
    const val HUB_START_DELAY_MS = 250L
    const val BACKOFF_RESET_AFTER_MS = 30_000L
    const val LONG_POLL_TIMEOUT_SECONDS = 120L
    const val MIN_TAIL_RECORDS = 5
    const val MAX_TAIL_RECORDS = 100
    const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
  }
}

internal class ClubspeedTimingAccumulator {
  private var heatNumber: String? = null
  private val histories = mutableMapOf<String, MutableMap<Int, Long>>()

  fun apply(scoreboard: JsonObject): List<TimingRow> {
    val competitors = scoreboard["ScoreboardData"] as? JsonArray ?: JsonArray(emptyList())
    val nextHeat = competitors.firstNotNullOfOrNull { (it as? JsonObject)?.text("HeatNo") }
    if (nextHeat != null && heatNumber != null && nextHeat != heatNumber) histories.clear()
    if (nextHeat != null) heatNumber = nextHeat

    return competitors.mapNotNull { element ->
      val row = element as? JsonObject ?: return@mapNotNull null
      val kart = row.text("AutoNo").orEmpty()
      val customer = row.text("CustID").orEmpty()
      val heat = row.text("HeatNo").orEmpty()
      val id = "clubspeed:$heat:$customer:$kart"
      val lap = row.text("LapNum")?.toIntOrNull()
      val lapMs = parseClubspeedTimeMs(row.text("LTime"))
      val history = histories.getOrPut(id) { mutableMapOf() }
      if (lap != null && lapMs != null && lap > 0) history[lap] = lapMs
      val lapHistory =
        history.entries.sortedByDescending(Map.Entry<Int, Long>::key).map { (number, timeMs) ->
          LapHistoryEntry(lap = number, lapMs = timeMs)
        }
      val bestLapMs = parseClubspeedTimeMs(row.text("BestLTime"))
      TimingRow(
        id = id,
        number = kart,
        name = row.text("RacerName").orEmpty(),
        position = row.text("Position")?.toIntOrNull(),
        lap = lap,
        lapMs = lapMs,
        recentCompletedLap = lap,
        recentCompletedLapMs = lapMs,
        bestLap = lapHistory.firstOrNull { it.lapMs == bestLapMs }?.lap,
        bestLapMs = bestLapMs,
        lapHistory = lapHistory,
        lapTimeline = lapHistory.map { LapTimelineEntry(lap = it.lap, lapMs = it.lapMs) },
      )
    }.sortedWith(compareBy<TimingRow> { it.position ?: Int.MAX_VALUE }.thenBy { it.number })
  }
}

internal fun parseClubspeedTimeMs(value: String?): Long? {
  val text = value?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: return null
  return runCatching {
    val parts = text.split(':')
    val seconds =
      when (parts.size) {
        1 -> BigDecimal(parts[0])
        2 -> BigDecimal(parts[0]).multiply(BigDecimal(60)).add(BigDecimal(parts[1]))
        3 ->
          BigDecimal(parts[0]).multiply(BigDecimal(3_600))
            .add(BigDecimal(parts[1]).multiply(BigDecimal(60)))
            .add(BigDecimal(parts[2]))
        else -> return null
      }
    seconds.multiply(BigDecimal(1_000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
  }.getOrNull()
}

internal fun shouldIgnoreClubspeedInvocationFailure(status: ConnectionStatus, error: Throwable): Boolean =
  status == ConnectionStatus.Connected && error is SocketTimeoutException

private fun JsonObject.text(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeUnless { it == "null" }

private fun JsonObject.requiredText(key: String): String = text(key) ?: error("Clubspeed response omitted $key")
