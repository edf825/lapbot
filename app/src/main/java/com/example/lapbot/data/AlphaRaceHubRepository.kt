package com.example.lapbot.data

import android.util.Log
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class AlphaRaceHubRepository(
  private val site: String = "buckmore",
  private val track: TimingTrack = TimingTracks.BuckmorePark,
) : TimingRepository {
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
  private val client =
    OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
      }
      .build()
  private val accumulator = TimingAccumulator()
  private val reconnectBackoff = ReconnectBackoff()
  private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(TimingUiState())
  override val state: kotlinx.coroutines.flow.StateFlow<TimingUiState> = mutableState

  private var token = ""
  private var cookie = ""
  private var pusherKey = PUSHER_KEY
  private var pusherCluster = "eu"
  private var channelSuffix = "live"
  private var socket: WebSocket? = null
  private var generation = 0
  private var wantsConnection = false
  private var reconnectJob: Job? = null
  private var backoffResetJob: Job? = null

  override fun connect(trackId: String) {
    scope.launch {
      if (mutableState.value.status != ConnectionStatus.Disconnected) return@launch
      Log.i(TAG, "Connect requested")
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
      backoffResetJob?.cancel()
      reconnectBackoff.reset()
      socket?.close(1000, "Disconnected")
      socket = null
      mutableState.value = mutableState.value.copy(status = ConnectionStatus.Disconnected, error = null)
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
      val boundedLimit = limit.coerceIn(MIN_TAIL_RECORDS, MAX_TAIL_RECORDS)
      mutableState.value =
        mutableState.value.copy(
          tailLimit = boundedLimit,
          jsonTail = mutableState.value.jsonTail.takeLast(boundedLimit),
        )
    }
  }

  override fun setReconnectPolicy(policy: ReconnectPolicy) {
    scope.launch {
      val boundedPolicy = policy.bounded()
      reconnectBackoff.updatePolicy(boundedPolicy)
      mutableState.value = mutableState.value.copy(reconnectPolicy = boundedPolicy)
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
    socket?.cancel()
    client.dispatcher.executorService.shutdown()
    scope.cancel()
  }

  private suspend fun open(activeGeneration: Int, reconnecting: Boolean) {
      mutableState.value =
        mutableState.value.copy(
          status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
          selectedTrackId = track.id,
          supportsSectors = track.supportsSectors,
        error = null,
        jsonTail = if (reconnecting) mutableState.value.jsonTail else emptyList(),
      )
    try {
      val streamAvailable = bootstrap()
      val snapshot =
        try {
          getCurrent()
        } catch (error: Exception) {
          if (streamAvailable) throw error
          JsonObject(emptyMap())
        }
      mutableState.value =
        mutableState.value.copy(
          rows = replaceSnapshot(snapshot, if (reconnecting) "reconnect" else "connect"),
        )
      if (!streamAvailable) {
        wantsConnection = false
        mutableState.value =
          mutableState.value.copy(
            status = ConnectionStatus.Disconnected,
            error = "No live stream is currently available. Showing the latest snapshot if one is available.",
          )
        return
      }
      openSocket(activeGeneration)
    } catch (error: Exception) {
      fail(activeGeneration, error)
    }
  }

  private suspend fun bootstrap(): Boolean {
    val response = client.newCall(Request.Builder().url("$BASE_URL/$site/live").build()).await()
    response.use {
      checkSuccessful(it)
      cookie = it.headers("Set-Cookie").joinToString("; ") { header -> header.substringBefore(';') }
      val rootTag = ROOT_TAG.find(it.body?.string().orEmpty())?.value ?: error("Live page root element was not found")
      val attributes =
        ATTRIBUTE.findAll(rootTag).associate { match ->
          match.groupValues[1].lowercase() to decodeHtml(match.groupValues[2])
        }
      token = attributes["data-pushertoken"].orEmpty()
      pusherKey = attributes["data-pusherkey"] ?: PUSHER_KEY
      pusherCluster = attributes["data-pushercluster"] ?: "eu"
      channelSuffix = attributes["data-pusherchannelsuffix"] ?: "live"
      return token.isNotBlank()
    }
  }

  private suspend fun getCurrent(eventId: String? = null): JsonObject {
    val url = buildString {
      append("$BASE_URL/api/v1/$site/live/current")
      if (eventId != null) append("?eventId=$eventId")
    }
    val response = client.newCall(apiRequest(url).build()).await()
    response.use {
      if (it.code == 204) return JsonObject(emptyMap())
      checkSuccessful(it)
      return json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
    }
  }

  private fun openSocket(activeGeneration: Int) {
    val url =
      "wss://ws-$pusherCluster.pusher.com/app/$pusherKey" +
        "?protocol=7&client=android&version=1.0&flash=false"
    val request = Request.Builder().url(url).header("Origin", BASE_URL).build()
    socket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(activeGeneration, webSocket, text) }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { fail(activeGeneration, IOException("Stream closed: $reason")) }
          }

          override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            scope.launch { fail(activeGeneration, error) }
          }
        },
      )
  }

  private suspend fun handleMessage(activeGeneration: Int, webSocket: WebSocket, text: String) {
    if (activeGeneration != generation) return
    try {
      val message = json.parseToJsonElement(text).jsonObject
      val event = message["event"]?.jsonPrimitive?.content
      val data = decodeData(message["data"])
      when (event) {
        "pusher:connection_established" -> {
          val socketId = data?.jsonObject?.get("socket_id")?.jsonPrimitive?.content ?: error("Pusher socket ID missing")
          val channel = "private-$site$channelSuffix"
          val auth = authorize(socketId, channel)
          webSocket.send(
            buildJsonObject {
              put("event", "pusher:subscribe")
              put(
                "data",
                buildJsonObject {
                  put("auth", auth)
                  put("channel", channel)
                },
              )
            }.toString(),
          )
        }
        "pusher_internal:subscription_succeeded" ->
          {
            Log.i(TAG, "WebSocket subscription connected")
            mutableState.value = mutableState.value.copy(status = ConnectionStatus.Connected, error = null)
            backoffResetJob?.cancel()
            backoffResetJob =
              scope.launch {
                delay(BACKOFF_RESET_AFTER_MS)
                if (mutableState.value.status == ConnectionStatus.Connected) reconnectBackoff.reset()
              }
          }
        "pusher:ping" -> webSocket.send("{\"event\":\"pusher:pong\",\"data\":{}}")
        "token" -> token = data?.jsonObject?.get("token")?.jsonPrimitive?.content ?: token
        "new_session", "refresh" -> {
          val eventId = data?.jsonObject?.get("eventUuid")?.jsonPrimitive?.content
          mutableState.value =
            mutableState.value.copy(rows = replaceSnapshot(getCurrent(eventId), event.orEmpty()))
        }
        "update" -> if (data != null) applyUpdate(data.jsonObject)
        "pusher:error" -> error("Pusher error: $data")
      }
    } catch (error: Exception) {
      webSocket.cancel()
      fail(activeGeneration, error)
    }
  }

  private suspend fun applyUpdate(patch: JsonObject) {
    if (patch["Clear"]?.jsonPrimitive?.content == "true") {
      Log.i(TAG, "Applying explicit stream clear")
      mutableState.value = mutableState.value.copy(rows = accumulator.replace(patch))
      return
    }
    val nextSequence = (patch["Sequence"] as? JsonPrimitive)?.content?.toLongOrNull()
    val currentSequence = accumulator.sequence
    if (currentSequence != null && nextSequence != currentSequence + 1) {
      Log.w(TAG, "Sequence gap: current=$currentSequence next=$nextSequence; fetching snapshot")
      mutableState.value = mutableState.value.copy(rows = replaceSnapshot(getCurrent(), "sequence_gap"))
      return
    }
    val events = accumulator.apply(patch).map(JsonObject::toString)
    mutableState.value =
      mutableState.value.copy(
        rows = accumulator.sortedRows(),
        jsonTail = (mutableState.value.jsonTail + events).takeLast(mutableState.value.tailLimit),
      )
  }

  private fun replaceSnapshot(snapshot: JsonObject, source: String): List<TimingRow> {
    val competitorCount = (snapshot["Competitors"] as? JsonArray)?.size ?: 0
    val previousCount = accumulator.sortedRows().size
    val rows = accumulator.replace(snapshot, preserveIfEmpty = true)
    if (competitorCount == 0 && previousCount > 0) {
      Log.w(TAG, "Ignoring empty $source snapshot; preserving $previousCount drivers")
    } else {
      Log.i(TAG, "Applied $source snapshot: sequence=${accumulator.sequence}, drivers=${rows.size}")
    }
    return rows
  }

  private suspend fun authorize(socketId: String, channel: String): String {
    val body = FormBody.Builder().add("socket_id", socketId).add("channel_name", channel).build()
    val request =
      apiRequest("$BASE_URL/pusher/auth")
        .header("Accept", "*/*")
        .header("Origin", BASE_URL)
        .header("Referer", "$BASE_URL/$site/live")
        .post(body)
        .build()
    val response = client.newCall(request).await()
    response.use {
      checkSuccessful(it)
      return json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["auth"]?.jsonPrimitive?.content
        ?: error("Pusher authorization was missing")
    }
  }

  private fun apiRequest(url: String): Request.Builder =
    Request.Builder()
      .url(url)
      .header("at-pst", token)
      .header("at-site", site)
      .header("Accept", "application/json")
      .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }

  private fun fail(activeGeneration: Int, error: Throwable) {
    if (activeGeneration != generation) return
    Log.w(TAG, "Stream failure", error)
    backoffResetJob?.cancel()
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
      reconnectJob?.cancel()
      reconnectJob =
        scope.launch {
          delay(delayMs)
          if (!wantsConnection) return@launch
          if (reconnectBackoff.expired()) {
            giveUpReconnecting()
            return@launch
          }
          generation += 1
          open(generation, reconnecting = true)
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

  private fun decodeData(element: JsonElement?): JsonElement? {
    var decoded = element ?: return null
    while (decoded is JsonPrimitive && decoded.isString) {
      decoded = runCatching { json.parseToJsonElement(decoded.content) }.getOrElse { return decoded }
    }
    return decoded
  }

  private fun decodeHtml(value: String): String =
    HTML_NUMERIC_ENTITY.replace(value) { match ->
      val encoded = match.groupValues[1]
      val codePoint =
        if (encoded.startsWith("x", ignoreCase = true)) encoded.drop(1).toInt(16)
        else encoded.toInt()
      String(Character.toChars(codePoint))
    }.replace("&amp;", "&")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")

  private fun checkSuccessful(response: Response) {
    if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")
  }

  private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }

          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
          }
        },
      )
    }

  private companion object {
    const val TAG = "LapbotStream"
    const val BASE_URL = "https://www.alpharacehub.com"
    const val PUSHER_KEY = "3aaffebc8193ea83cb2f"
    const val MIN_TAIL_RECORDS = 5
    const val MAX_TAIL_RECORDS = 100
    const val BACKOFF_RESET_AFTER_MS = 30_000L
    const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
    val ROOT_TAG = Regex("<[^>]*\\bid=[\\\"']root[\\\"'][^>]*>", RegexOption.IGNORE_CASE)
    val ATTRIBUTE = Regex("([\\w-]+)=[\\\"']([^\\\"']*)[\\\"']")
    val HTML_NUMERIC_ENTITY = Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)
  }
}

private fun ReconnectPolicy.bounded(): ReconnectPolicy {
  val initial = initialDelayMs.coerceIn(500, 10_000)
  val maximum = maxDelayMs.coerceIn(initial, 60_000)
  return copy(
    initialDelayMs = initial,
    maxDelayMs = maximum,
    giveUpAfterMs = giveUpAfterMs.coerceIn(60_000, 30 * 60_000),
  )
}
