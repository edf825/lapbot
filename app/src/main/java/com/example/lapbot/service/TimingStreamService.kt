package com.example.lapbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.lapbot.MainActivity
import com.example.lapbot.data.AlphaRaceHubRepository
import com.example.lapbot.data.ClubspeedTimingRepository
import com.example.lapbot.data.AnnouncementVoiceGender
import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.CoachingObjectiveUiState
import com.example.lapbot.data.DemoTimingReplay
import com.example.lapbot.data.ReconnectPolicy
import com.example.lapbot.data.TimingServiceState
import com.example.lapbot.data.TimingRepository
import com.example.lapbot.data.TimingTracks
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.ToneMetric
import com.example.lapbot.data.ToneSettings
import com.example.lapbot.data.canonicalKartNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class TimingStreamService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var repository: TimingRepository? = null
  private var repositoryStateJob: Job? = null
  private lateinit var notificationManager: NotificationManager
  private var foreground = false
  private var textToSpeech: TextToSpeech? = null
  private var textToSpeechReady = false
  private var pendingAnnouncement: PendingAnnouncement? = null
  private lateinit var tonePlayer: TonePlayer
  private lateinit var sectorSoundPlayer: SectorSoundPlayer
  private val utteranceCompletions = ConcurrentHashMap<String, UtteranceCompletion>()
  private var pendingToneLap: Int? = null
  private var pendingToneSpeechComplete = false
  private val observedCompletedLaps = mutableMapOf<String, Int>()
  private val knownDriverIds = mutableSetOf<String>()
  private val observedSectorUpdates = mutableSetOf<SectorUpdateKey>()
  private var observedSectorKartNumber: String? = null
  private val sessionCoach = SessionCoach()
  private var coachingObjectiveUi = CoachingObjectiveUiState()
  private var demoActive = false
  private var demoJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "Service created")
    TimingServiceState.running = true
    tonePlayer = TonePlayer(scope)
    sectorSoundPlayer = SectorSoundPlayer(this)
    notificationManager = getSystemService(NotificationManager::class.java)
    createNotificationChannel()
    textToSpeech =
      TextToSpeech(this) { status ->
        if (status == TextToSpeech.SUCCESS) {
          textToSpeech?.language = Locale.UK
          textToSpeechReady = true
          pendingAnnouncement?.let(::speak)
          pendingAnnouncement = null
        }
      }
    textToSpeech?.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
          val completion = utteranceCompletions.remove(utteranceId) ?: return
          scope.launch {
            if (completion.lap != null && pendingToneLap == completion.lap) {
              pendingToneSpeechComplete = true
              playPendingTones(TimingServiceState.mutableState.value)
            }
            if (completion.stopServiceAfter && !foreground) stopSelf()
          }
        }

        override fun onError(utteranceId: String) {
          val completion = utteranceCompletions.remove(utteranceId)
          scope.launch {
            clearPendingTone(completion?.lap)
            if (completion?.stopServiceAfter == true && !foreground) stopSelf()
          }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
          val completion = utteranceCompletions.remove(utteranceId)
          scope.launch {
            clearPendingTone(completion?.lap)
            if (completion?.stopServiceAfter == true && !foreground) stopSelf()
          }
        }
      },
    )

    configureRepository(AlphaRaceHubRepository(), connect = false)
  }

  private fun configureRepository(nextRepository: TimingRepository, connect: Boolean, trackId: String? = null) {
    repositoryStateJob?.cancel()
    repository?.close()
    repository = nextRepository
    val current = TimingServiceState.mutableState.value
    nextRepository.setAutoReconnect(current.autoReconnect)
    nextRepository.setTailLimit(current.tailLimit)
    nextRepository.setReconnectPolicy(current.reconnectPolicy)
    repositoryStateJob = scope.launch {
      nextRepository.state.collectLatest { repositoryState ->
        if (demoActive) return@collectLatest
        val settings = TimingServiceState.mutableState.value
        val state =
          repositoryState.copy(
            autoReconnect = settings.autoReconnect,
            tailLimit = settings.tailLimit,
            reconnectPolicy = settings.reconnectPolicy,
            selectedKartNumber = settings.selectedKartNumber,
            metricsSinceLap = settings.metricsSinceLap,
            coachEnabled = settings.coachEnabled,
            announcementSettings = settings.announcementSettings,
            toneSettings = settings.toneSettings,
            jsonTail = repositoryState.jsonTail.takeLast(settings.tailLimit),
          )
        observeCompletedLaps(state)
        TimingServiceState.mutableState.value = state.copy(coachingObjective = coachingObjectiveUi)
        playPendingTones(state)
        if (foreground) notificationManager.notify(NOTIFICATION_ID, streamNotification(state))
      }
    }
    if (connect && trackId != null) nextRepository.connect(trackId)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.i(TAG, "Service command: ${intent?.action ?: "restart"}")
    when (intent?.action) {
      ACTION_CONNECT -> {
        demoActive = false
        demoJob?.cancel()
        observedCompletedLaps.clear()
        knownDriverIds.clear()
        observedSectorUpdates.clear()
        observedSectorKartNumber = null
        sessionCoach.reset()
        coachingObjectiveUi = CoachingObjectiveUiState()
        startInForeground()
        val trackId = intent.getStringExtra(EXTRA_TRACK_ID) ?: TimingTracks.BuckmorePark.id
        val track = TimingTracks.find(trackId) ?: TimingTracks.BuckmorePark
        val nextRepository =
          when (track.id) {
            TimingTracks.DaytonaSandownParkGp.id -> ClubspeedTimingRepository()
            else -> AlphaRaceHubRepository()
          }
        configureRepository(nextRepository, connect = true, trackId = track.id)
      }
      ACTION_START_DEMO -> startDemo()
      ACTION_DISCONNECT -> stopStreaming()
      ACTION_SET_AUTO_RECONNECT ->
        repository?.setAutoReconnect(intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, false))
      ACTION_SET_TAIL_LIMIT ->
        repository?.setTailLimit(intent.getIntExtra(EXTRA_TAIL_LIMIT, DEFAULT_TAIL_LIMIT))
      ACTION_SET_RECONNECT_POLICY ->
        repository?.setReconnectPolicy(
          ReconnectPolicy(
            initialDelayMs = intent.getLongExtra(EXTRA_INITIAL_DELAY_MS, 500),
            maxDelayMs = intent.getLongExtra(EXTRA_MAX_DELAY_MS, 60_000),
            giveUpAfterMs = intent.getLongExtra(EXTRA_GIVE_UP_AFTER_MS, 10 * 60_000),
          ),
        )
      ACTION_SET_TONE_SETTINGS ->
        TimingServiceState.mutableState.value =
          TimingServiceState.mutableState.value.copy(
            toneSettings =
              ToneSettings(
                enabled = intent.getBooleanExtra(EXTRA_TONES_ENABLED, true),
                metric =
                  intent.getStringExtra(EXTRA_TONE_METRIC)?.let { name ->
                    runCatching { ToneMetric.valueOf(name) }.getOrNull()
                  } ?: ToneMetric.DriverBest,
                referenceDurationMs = intent.getIntExtra(EXTRA_REFERENCE_DURATION_MS, 200),
                lapDurationMs = intent.getIntExtra(EXTRA_LAP_DURATION_MS, 300),
                sectorDurationMs = intent.getIntExtra(EXTRA_SECTOR_DURATION_MS, 150),
              ),
          )
      ACTION_PLAY_TEST_TONES -> playTones(TimingServiceState.mutableState.value)
      ACTION_PREVIEW_ANNOUNCEMENT -> previewAnnouncement()
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "Foreground data-sync timeout")
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        status = ConnectionStatus.Disconnected,
        isDemo = false,
        error = "Android's background streaming limit was reached. Open Lapbot to reconnect.",
      )
    repository?.disconnect()
    stopForeground(STOP_FOREGROUND_REMOVE)
    foreground = false
    notificationManager.notify(NOTIFICATION_ID, timeoutNotification())
    stopSelf(startId)
  }

  override fun onDestroy() {
    Log.i(TAG, "Service destroyed")
    TimingServiceState.running = false
    demoJob?.cancel()
    repositoryStateJob?.cancel()
    repository?.close()
    tonePlayer.stop()
    sectorSoundPlayer.release()
    textToSpeech?.shutdown()
    scope.cancel()
    super.onDestroy()
  }

  private fun startInForeground() {
    if (foreground) return
    val notification = streamNotification(TimingUiState(status = ConnectionStatus.Connecting))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    foreground = true
  }

  private fun observeCompletedLaps(state: com.example.lapbot.data.TimingUiState) {
    val selectedKartNumber = state.selectedKartNumber
    val selectionChanged = observedSectorKartNumber != selectedKartNumber
    if (selectionChanged) {
      observedSectorKartNumber = selectedKartNumber
      observedSectorUpdates.clear()
    }
    state.rows.forEach { driver ->
      val isNewDriver = knownDriverIds.add(driver.id)
      val isSelected = canonicalKartNumber(driver.number) == state.selectedKartNumber
      if (isNewDriver && state.status == ConnectionStatus.Connected && isSelected) {
        observedCompletedLaps[driver.id] =
          if (driver.lapMs != null) (driver.lap ?: 1) - 1
          else driver.recentCompletedLap ?: ((driver.lap ?: 1) - 1)
      }
      if (isSelected && (selectionChanged || isNewDriver)) {
        val latestCompletedLap = driver.recentCompletedLap ?: driver.lap
        if (latestCompletedLap != null) {
          // The session snapshot is already useful context. Seed the card without speaking
          // a historical announcement, then continue learning from live updates.
          coachingObjectiveUi = sessionCoach.onLap(driver, latestCompletedLap, state.announcementSettings.coachingChattiness).objectiveUi
        }
      }
      if (isSelected) observeSectorDeltas(state, driver, seedOnly = selectionChanged || isNewDriver)
      val completedLap = driver.lap ?: return@forEach
      val lapTime = driver.lapMs ?: return@forEach
      val previousLap = observedCompletedLaps.put(driver.id, completedLap)
      val isNewSelectedLap = isSelected && previousLap != null && completedLap > previousLap
      val finalSectorCoaching =
        if (isNewSelectedLap) {
          driver.sector3Ms?.let { sectorTime ->
            sessionCoach.onSector(
              driver,
              completedLap,
              sector = 3,
              timeMs = sectorTime,
              chattiness = state.announcementSettings.coachingChattiness,
            ).sectorObservation
          }
        } else null
      val lapCoaching =
        if (isNewSelectedLap) sessionCoach.onLap(driver, completedLap, state.announcementSettings.coachingChattiness)
        else CoachingResult(objectiveUi = sessionCoach.objectiveUi(driver.id))
      if (isSelected) coachingObjectiveUi = lapCoaching.objectiveUi
      if (state.coachEnabled && isNewSelectedLap) {
        pendingToneLap = completedLap
        pendingToneSpeechComplete = false
        val previousLapTime =
          driver.lapTimeline
            .firstOrNull { lap -> lap.lap < completedLap && lap.lapMs != null }
            ?.lapMs
        val observedPreviousBestTime =
          driver.lapHistory
            .filterNot { lap -> lap.lap == completedLap }
            .minOfOrNull { lap -> lap.lapMs }
        val serverPreviousBestTime = driver.bestLapMs?.takeIf { it < lapTime }
        val previousBestTime = listOfNotNull(observedPreviousBestTime, serverPreviousBestTime).minOrNull()
        val lastDelta = previousLapTime?.let { lapTime - it }
        val bestDelta = previousBestTime?.let { lapTime - it }
        val coachingMessage =
          if (state.announcementSettings.speakCoaching) {
            listOfNotNull(lapCoaching.lapObservation, finalSectorCoaching).maxByOrNull(CoachingObservation::priority)?.text
          } else null
        announce(
          formatBudgetedLapAnnouncementSections(
            lapTimeMs = lapTime,
            lastDeltaMs =
              if (state.announcementSettings.speakLastComparison) lastDelta
              else null,
            bestDeltaMs =
              if (state.announcementSettings.speakBestComparison) bestDelta
              else null,
            coaching = coachingMessage,
          ),
          lap = completedLap,
          sectorSound =
            if (state.announcementSettings.sectorTonesEnabled) lapCompletionSound(bestDelta) else null,
        )
      }
    }
  }

  private fun announce(
    sections: List<String>,
    lap: Int? = null,
    stopServiceAfter: Boolean = false,
    sectorSound: SectorSound? = null,
  ) {
    if (sectorSound != null) {
      sectorSoundPlayer.play(sectorSound)
      scope.launch {
        delay(SECTOR_SPEECH_DELAY_MS)
        announce(sections, lap, stopServiceAfter)
      }
      return
    }
    val announcement = PendingAnnouncement(sections, lap, stopServiceAfter)
    if (textToSpeechReady) speak(announcement) else pendingAnnouncement = announcement
  }

  private fun speak(announcement: PendingAnnouncement) {
    val tts = textToSpeech ?: return
    configureVoice(tts, TimingServiceState.mutableState.value.announcementSettings.voiceGender)
    tts.setSpeechRate(TimingServiceState.mutableState.value.announcementSettings.speechRate)
    tts.setPitch(NATURAL_SPEECH_PITCH)
    val utteranceId = "announcement-${System.nanoTime()}"
    utteranceCompletions[utteranceId] =
      UtteranceCompletion(announcement.lap, announcement.stopServiceAfter)
    tts.speak(
      announcement.sections.joinToString(separator = ". ", postfix = "."),
      TextToSpeech.QUEUE_FLUSH,
      null,
      utteranceId,
    )
  }

  private fun configureVoice(tts: TextToSpeech, gender: AnnouncementVoiceGender) {
    tts.language = Locale.UK
    val selectedVoice = selectPreferredVoice(tts.voices.orEmpty(), gender)
    selectedVoice?.let { tts.voice = it }
    Log.i(TAG, "TTS voice ${selectedVoice?.name ?: tts.voice?.name ?: "default"} for ${gender.name.lowercase()}")
  }

  private fun observeSectorDeltas(state: TimingUiState, driver: com.example.lapbot.data.TimingRow, seedOnly: Boolean) {
    val lap = driver.lap ?: return
    listOf(1 to driver.sector1Ms, 2 to driver.sector2Ms).forEach { (sector, timeMs) ->
      if (timeMs == null) return@forEach
      val isNewUpdate = observedSectorUpdates.add(SectorUpdateKey(driver.id, lap, sector))
      if (isNewUpdate && !seedOnly && driver.lapMs == null) {
        val coaching = sessionCoach.onSector(driver, lap, sector, timeMs, state.announcementSettings.coachingChattiness)
        coachingObjectiveUi = coaching.objectiveUi
        val speakRaw = state.announcementSettings.speakSectorDeltas
        val speakCoaching = state.announcementSettings.speakCoaching && coaching.sectorObservation != null
        if (!state.coachEnabled || (!speakRaw && !speakCoaching)) return@forEach
        val delta = sectorDeltaToPersonalBest(driver, sector)
        val sound = if (delta?.isNewBest == true) SectorSound.Best else SectorSound.Standard
        val sections =
          buildList {
            if (speakRaw) {
              add(
                delta?.let {
                  formatSectorDeltaAnnouncement(it, includeBestComparison = state.announcementSettings.speakBestComparison && !speakCoaching)
                } ?: formatSpokenHundredths(timeMs),
              )
            }
            if (speakCoaching) add(requireNotNull(coaching.sectorObservation).text)
          }
        Log.i(TAG, "Sector $sector time ${timeMs}ms, sound ${sound.name}, coaching=$speakCoaching")
        announce(
          sections = sections,
          sectorSound = if (state.announcementSettings.sectorTonesEnabled) sound else null,
        )
      }
    }
  }

  private fun previewAnnouncement() {
    announce(
      sections =
        listOf(
          "Great lap",
          "52 point 34",
          "Point 22 quicker than last",
          "Biggest gain, sector two, point 18",
        ),
      stopServiceAfter = !foreground,
    )
  }

  private fun playPendingTones(state: com.example.lapbot.data.TimingUiState) {
    val lap = pendingToneLap ?: return
    if (!pendingToneSpeechComplete) return
    if (!state.toneSettings.enabled) {
      pendingToneLap = null
      pendingToneSpeechComplete = false
      return
    }
    val sequence = buildToneSequence(state, lap) ?: return
    pendingToneLap = null
    pendingToneSpeechComplete = false
    tonePlayer.play(sequence)
  }

  private fun clearPendingTone(lap: Int?) {
    if (lap != null && pendingToneLap == lap) {
      pendingToneLap = null
      pendingToneSpeechComplete = false
    }
  }

  private fun playTones(state: com.example.lapbot.data.TimingUiState) {
    val sequence = buildToneSequence(state)
    if (sequence == null) {
      Log.w(TAG, "Test tones unavailable for selected metric")
    } else {
      tonePlayer.play(sequence)
    }
  }

  private fun startDemo() {
    Log.i(TAG, "Starting demo replay for session 837888")
    demoActive = true
    demoJob?.cancel()
    repository?.disconnect()
    observedCompletedLaps.clear()
    knownDriverIds.clear()
    observedSectorUpdates.clear()
    observedSectorKartNumber = null
    sessionCoach.reset()
    coachingObjectiveUi = CoachingObjectiveUiState()
    pendingToneLap = null
    pendingToneSpeechComplete = false
    startInForeground()

    val replay = DemoTimingReplay()
    val settings = TimingServiceState.mutableState.value
    val initial =
      settings.copy(
        status = ConnectionStatus.Connected,
        isDemo = true,
        selectedTrackId = TimingTracks.BuckmorePark.id,
        supportsSectors = true,
        rows = replay.initialRows(),
        jsonTail = emptyList(),
        selectedKartNumber = "5",
        error = null,
      )
    observeCompletedLaps(initial)
    TimingServiceState.mutableState.value = initial.copy(coachingObjective = coachingObjectiveUi)
    notificationManager.notify(NOTIFICATION_ID, streamNotification(initial))

    demoJob =
      scope.launch {
        delay(replay.initialDelayMs())
        while (demoActive) {
          val frame = replay.advance()
          val current = TimingServiceState.mutableState.value
          val state =
            current.copy(
              status = ConnectionStatus.Connected,
              isDemo = true,
              rows = frame.rows,
              jsonTail = (current.jsonTail + frame.records).takeLast(current.tailLimit),
              error = null,
            )
          observeCompletedLaps(state)
          TimingServiceState.mutableState.value = state.copy(coachingObjective = coachingObjectiveUi)
          playPendingTones(state)
          if (foreground) notificationManager.notify(NOTIFICATION_ID, streamNotification(state))
          delay(frame.nextDelayMs)
        }
      }
  }

  private fun stopStreaming() {
    demoActive = false
    demoJob?.cancel()
    repository?.disconnect()
    observedSectorUpdates.clear()
    observedSectorKartNumber = null
    sessionCoach.reset()
    coachingObjectiveUi = CoachingObjectiveUiState()
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        status = ConnectionStatus.Disconnected,
        isDemo = false,
        selectedTrackId = null,
        supportsSectors = true,
        error = null,
      )
    stopForeground(STOP_FOREGROUND_REMOVE)
    foreground = false
    stopSelf()
  }

  private fun streamNotification(state: TimingUiState): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(if (state.isDemo) "Lapbot demo replay" else "Lapbot live timing")
      .setContentText(if (state.isDemo) "Replaying session 837888" else state.status.notificationText)
      .setContentIntent(openAppIntent())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(0, "Disconnect", servicePendingIntent(ACTION_DISCONNECT))
      .build()

  private fun timeoutNotification(): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_error)
      .setContentTitle("Lapbot stream paused")
      .setContentText("Open Lapbot to restore background streaming.")
      .setContentIntent(openAppIntent())
      .setAutoCancel(true)
      .build()

  private fun openAppIntent(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun servicePendingIntent(action: String): PendingIntent =
    PendingIntent.getService(
      this,
      action.hashCode(),
      Intent(this, TimingStreamService::class.java).setAction(action),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Live timing", NotificationManager.IMPORTANCE_LOW),
      )
    }
  }

  companion object {
    private const val TAG = "LapbotService"
    const val ACTION_CONNECT = "com.example.lapbot.action.CONNECT"
    const val ACTION_START_DEMO = "com.example.lapbot.action.START_DEMO"
    const val ACTION_DISCONNECT = "com.example.lapbot.action.DISCONNECT"
    const val ACTION_SET_AUTO_RECONNECT = "com.example.lapbot.action.SET_AUTO_RECONNECT"
    const val ACTION_SET_TAIL_LIMIT = "com.example.lapbot.action.SET_TAIL_LIMIT"
    const val ACTION_SET_RECONNECT_POLICY = "com.example.lapbot.action.SET_RECONNECT_POLICY"
    const val ACTION_SET_TONE_SETTINGS = "com.example.lapbot.action.SET_TONE_SETTINGS"
    const val ACTION_PLAY_TEST_TONES = "com.example.lapbot.action.PLAY_TEST_TONES"
    const val ACTION_PREVIEW_ANNOUNCEMENT = "com.example.lapbot.action.PREVIEW_ANNOUNCEMENT"
    const val EXTRA_AUTO_RECONNECT = "autoReconnect"
    const val EXTRA_TAIL_LIMIT = "tailLimit"
    const val EXTRA_INITIAL_DELAY_MS = "initialDelayMs"
    const val EXTRA_MAX_DELAY_MS = "maxDelayMs"
    const val EXTRA_GIVE_UP_AFTER_MS = "giveUpAfterMs"
    const val EXTRA_TONE_METRIC = "toneMetric"
    const val EXTRA_TONES_ENABLED = "tonesEnabled"
    const val EXTRA_REFERENCE_DURATION_MS = "referenceDurationMs"
    const val EXTRA_LAP_DURATION_MS = "lapDurationMs"
    const val EXTRA_SECTOR_DURATION_MS = "sectorDurationMs"
    const val EXTRA_TRACK_ID = "trackId"
    private const val DEFAULT_TAIL_LIMIT = 20
    private const val CHANNEL_ID = "live_timing"
    private const val NOTIFICATION_ID = 1001
    private const val NATURAL_SPEECH_PITCH = 0.98f
    private const val SECTOR_SPEECH_DELAY_MS = 1_000L
  }
}

private data class PendingAnnouncement(
  val sections: List<String>,
  val lap: Int?,
  val stopServiceAfter: Boolean,
)

private data class UtteranceCompletion(
  val lap: Int?,
  val stopServiceAfter: Boolean,
)

private data class SectorUpdateKey(val driverId: String, val lap: Int, val sector: Int)

internal data class SectorDelta(val sector: Int, val sectorTimeMs: Long, val deltaMs: Long) {
  val isNewBest: Boolean
    get() = deltaMs < 0
}

internal fun lapCompletionSound(bestDeltaMs: Long?): SectorSound =
  if (bestDeltaMs != null && bestDeltaMs < 0) SectorSound.Best else SectorSound.Standard

internal fun sectorDeltaToPersonalBest(driver: com.example.lapbot.data.TimingRow, sector: Int): SectorDelta? {
  val current =
    when (sector) {
      1 -> driver.sector1Ms
      2 -> driver.sector2Ms
      else -> null
    } ?: return null
  val previousBest =
    driver.lapHistory.mapNotNull { lap ->
      when (sector) {
        1 -> lap.sector1Ms
        2 -> lap.sector2Ms
        else -> null
      }
    }.minOrNull() ?: return null
  return SectorDelta(sector, current, current - previousBest)
}

internal fun formatSectorDeltaAnnouncement(
  delta: SectorDelta,
  includeBestComparison: Boolean = true,
): String =
  buildString {
    append(formatSpokenHundredths(delta.sectorTimeMs))
    if (includeBestComparison && delta.isNewBest) {
      append(", new PB")
    } else if (includeBestComparison) {
      append(", ")
      append(formatSpokenDeltaMagnitude(delta.deltaMs))
      append(" off best")
    }
  }

internal fun selectPreferredVoice(
  voices: Set<Voice>,
  gender: AnnouncementVoiceGender,
): Voice? =
  voices
    .asSequence()
    .filter { it.locale.language == Locale.ENGLISH.language }
    .filter { it.locale.country == Locale.UK.country }
    .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
    .filter { inferVoiceGender(it.name) == gender }
    .maxWithOrNull(
      compareBy<Voice> { it.isNetworkConnectionRequired }
        .thenBy { it.quality }
        .thenByDescending { it.latency },
    )

internal fun inferVoiceGender(voiceName: String): AnnouncementVoiceGender? {
  val normalized = voiceName.lowercase(Locale.ROOT)
  if ("female" in normalized) return AnnouncementVoiceGender.Female
  if ("male" in normalized) return AnnouncementVoiceGender.Male
  val googleBritishVariant = Regex("en[-_]gb[-_]x[-_]gb([a-z])").find(normalized)?.groupValues?.get(1)
  return when (googleBritishVariant) {
    "a", "c", "e", "g" -> AnnouncementVoiceGender.Female
    "b", "d", "f", "h" -> AnnouncementVoiceGender.Male
    else -> null
  }
}

internal fun formatLapAnnouncementSections(
  lapTimeMs: Long,
  lastDeltaMs: Long? = null,
  bestDeltaMs: Long? = null,
  sectorInsight: String? = null,
  encouragement: String? = null,
): List<String> =
  buildList {
    encouragement?.let(::add)
    add(formatSpokenHundredths(lapTimeMs))
    lastDeltaMs?.let { add(formatLastComparison(it)) }
    bestDeltaMs?.let { add(formatBestComparison(it)) }
    sectorInsight?.let(::add)
  }

internal fun formatLapAnnouncement(
  lapTimeMs: Long,
  lastDeltaMs: Long? = null,
  bestDeltaMs: Long? = null,
  sectorInsight: String? = null,
  encouragement: String? = null,
): String =
  formatLapAnnouncementSections(
    lapTimeMs = lapTimeMs,
    lastDeltaMs = lastDeltaMs,
    bestDeltaMs = bestDeltaMs,
    sectorInsight = sectorInsight,
    encouragement = encouragement,
  ).joinToString(". ")

/** Keeps coaching useful without turning a lap call into a list of independent facts. */
internal fun formatBudgetedLapAnnouncementSections(
  lapTimeMs: Long,
  lastDeltaMs: Long?,
  bestDeltaMs: Long?,
  coaching: String?,
): List<String> =
  buildList {
    add(formatSpokenHundredths(lapTimeMs))
    if (coaching == null) {
      lastDeltaMs?.let { add(formatLastComparison(it)) }
      bestDeltaMs?.let { add(formatBestComparison(it)) }
    } else {
      val selectedComparison: Pair<Long, (Long) -> String>? =
        when {
          bestDeltaMs != null && bestDeltaMs <= 0 -> bestDeltaMs to ::formatBestComparison
          lastDeltaMs != null && bestDeltaMs != null && abs(lastDeltaMs) >= abs(bestDeltaMs) -> lastDeltaMs to ::formatLastComparison
          bestDeltaMs != null -> bestDeltaMs to ::formatBestComparison
          lastDeltaMs != null -> lastDeltaMs to ::formatLastComparison
          else -> null
        }
      selectedComparison?.let { (delta, formatter) -> add(formatter(delta)) }
      add(coaching)
    }
  }

private fun formatLastComparison(milliseconds: Long): String =
  when {
    milliseconds > 0 -> "${formatSpokenDeltaMagnitude(milliseconds).sentenceCase()} slower than last"
    milliseconds < 0 -> "${formatSpokenDeltaMagnitude(kotlin.math.abs(milliseconds)).sentenceCase()} quicker than last"
    else -> "Same as last"
  }

private fun formatBestComparison(milliseconds: Long): String =
  when {
    milliseconds > 0 -> "${formatSpokenDeltaMagnitude(milliseconds).sentenceCase()} off your best"
    milliseconds < 0 -> "New best by ${formatSpokenDeltaMagnitude(kotlin.math.abs(milliseconds))}"
    else -> "Matches your best"
  }

internal fun formatSpokenDeltaMagnitude(milliseconds: Long): String {
  val hundredths = milliseconds / 10
  val wholeSeconds = hundredths / 100
  val fraction = (hundredths % 100).toString().padStart(2, '0')
  return if (wholeSeconds == 0L) "point $fraction" else "$wholeSeconds point $fraction"
}

private fun String.sentenceCase(): String = replaceFirstChar(Char::uppercase)

internal fun formatSpokenHundredths(milliseconds: Long): String {
  val hundredths = milliseconds / 10
  return "${hundredths / 100} point ${(hundredths % 100).toString().padStart(2, '0')}"
}

private val ConnectionStatus.notificationText: String
  get() =
    when (this) {
      ConnectionStatus.Disconnected -> "Disconnected"
      ConnectionStatus.Connecting -> "Connecting to Buckmore..."
      ConnectionStatus.Connected -> "Receiving live timing"
      ConnectionStatus.Reconnecting -> "Connection interrupted; reconnecting..."
    }
