package com.example.lapbot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lapbot.MainActivity
import com.example.lapbot.data.AlphaRaceHubRepository
import com.example.lapbot.data.ClubspeedTimingRepository
import com.example.lapbot.data.AnnouncementVoiceGender
import com.example.lapbot.data.ConnectionStatus
import com.example.lapbot.data.CoachingChattiness
import com.example.lapbot.data.CoachingObjectiveUiState
import com.example.lapbot.data.DemoTimingReplay
import com.example.lapbot.data.ReconnectPolicy
import com.example.lapbot.data.SessionHistoryStore
import com.example.lapbot.data.SessionRecorder
import com.example.lapbot.data.RelativeOpportunityUiState
import com.example.lapbot.data.PREF_COACHING_CHATTINESS
import com.example.lapbot.data.PREF_SPEAK_SECTOR_DELTAS
import com.example.lapbot.data.TIMING_PREFERENCES_NAME
import com.example.lapbot.data.TimingServiceState
import com.example.lapbot.data.TimingRepository
import com.example.lapbot.data.TimingTracks
import com.example.lapbot.data.TimingUiState
import com.example.lapbot.data.TeamSportTimingRepository
import com.example.lapbot.data.ToneMetric
import com.example.lapbot.data.ToneSettings
import com.example.lapbot.data.canonicalKartNumber
import com.example.lapbot.data.findDriverByNameFragment
import com.example.lapbot.data.toTimingRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class TimingStreamService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var repository: TimingRepository? = null
  private var repositoryStateJob: Job? = null
  private lateinit var notificationManager: NotificationManager
  private var foreground = false
  private var textToSpeech: TextToSpeech? = null
  private var textToSpeechReady = false
  private lateinit var voiceCommandListener: VoiceCommandListener
  private var voiceCommandListening = false
  private var commandAcknowledgement: ToneGenerator? = null
  private val announcementQueue = PlaybackMessageQueue<PendingAnnouncement>()
  private val activeAnnouncement: PendingAnnouncement?
    get() = announcementQueue.active
  private var activeUtteranceId: String? = null
  private var announcementDelayJob: Job? = null
  private var tonesPlaying = false
  private var stopWhenAudioQueueDrains = false
  private lateinit var tonePlayer: TonePlayer
  private lateinit var sectorSoundPlayer: SectorSoundPlayer
  private var pendingToneLap: Int? = null
  private var pendingToneSpeechComplete = false
  private var gapAnnouncementJob: Job? = null
  private val observedCompletedLaps = mutableMapOf<String, Int>()
  private val knownDriverIds = mutableSetOf<String>()
  private val observedSectorUpdates = mutableSetOf<SectorUpdateKey>()
  private var observedSectorKartNumber: String? = null
  private val sessionCoach = SessionCoach()
  private var coachingObjectiveUi = CoachingObjectiveUiState()
  private var relativeOpportunityUi = RelativeOpportunityUiState()
  private var coachingSessionKey: String? = null
  private var demoActive = false
  private var demoJob: Job? = null
  private lateinit var sessionStore: SessionHistoryStore
  private lateinit var sessionRecorder: SessionRecorder

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "Service created")
    TimingServiceState.running = true
    tonePlayer = TonePlayer(scope)
    sectorSoundPlayer = SectorSoundPlayer(this)
    sessionStore = SessionHistoryStore(this)
    sessionRecorder = SessionRecorder(sessionStore)
    commandAcknowledgement = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    voiceCommandListener = VoiceCommandListener(this, ::handleVoiceCommand)
    notificationManager = getSystemService(NotificationManager::class.java)
    createNotificationChannel()
    textToSpeech =
      TextToSpeech(this) { status ->
        if (status == TextToSpeech.SUCCESS) {
          textToSpeech?.language = Locale.UK
          textToSpeech?.setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build(),
          )
          textToSpeechReady = true
          drainAnnouncementQueue()
        } else {
          Log.e(TAG, "Text-to-speech initialization failed: $status")
          announcementQueue.clear()
          voiceCommandListener.resumeAfterPlayback()
        }
      }
    textToSpeech?.setOnUtteranceProgressListener(
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
          voiceCommandListener.pauseForPlayback()
        }

        override fun onDone(utteranceId: String) {
          scope.launch { completeAnnouncement(utteranceId, succeeded = true) }
        }

        override fun onError(utteranceId: String) {
          scope.launch { completeAnnouncement(utteranceId, succeeded = false) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
          scope.launch { completeAnnouncement(utteranceId, succeeded = false) }
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
      nextRepository.state.collect { repositoryState ->
        if (demoActive) return@collect
        val settings = TimingServiceState.mutableState.value
        val autoDetectedKartNumber =
          settings.autoDetectDriverName?.let { fragment ->
            findDriverByNameFragment(repositoryState.rows, fragment)?.number?.let(::canonicalKartNumber)
          }
        val state =
          repositoryState.copy(
            autoReconnect = settings.autoReconnect,
            tailLimit = settings.tailLimit,
            reconnectPolicy = settings.reconnectPolicy,
            selectedKartNumber =
              if (settings.autoDetectDriverName != null) autoDetectedKartNumber else settings.selectedKartNumber,
            autoDetectDriverName = settings.autoDetectDriverName,
            metricsSinceLap = settings.metricsSinceLap,
            coachEnabled = settings.coachEnabled,
            listenForCommands = settings.listenForCommands,
            announcementSettings = settings.announcementSettings,
            toneSettings = settings.toneSettings,
            jsonTail = repositoryState.jsonTail.takeLast(settings.tailLimit),
          )
        if (state.sessionKey != null && coachingSessionKey != state.sessionKey) {
          gapAnnouncementJob?.cancel()
          sessionCoach.reset()
          coachingObjectiveUi = CoachingObjectiveUiState()
          relativeOpportunityUi = RelativeOpportunityUiState()
          observedCompletedLaps.clear()
          knownDriverIds.clear()
          observedSectorUpdates.clear()
          coachingSessionKey = state.sessionKey
        }
        // Establish the durable recording before lap processing can emit its first announcement.
        sessionRecorder.observe(state)
        observeCompletedLaps(state)
        val finalState = state.copy(coachingObjective = coachingObjectiveUi, relativeOpportunity = relativeOpportunityUi)
        TimingServiceState.mutableState.value = finalState
        sessionRecorder.observe(finalState)
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
        clearAudioQueue()
        demoActive = false
        demoJob?.cancel()
        observedCompletedLaps.clear()
        knownDriverIds.clear()
        observedSectorUpdates.clear()
        observedSectorKartNumber = null
        gapAnnouncementJob?.cancel()
        sessionCoach.reset()
        coachingObjectiveUi = CoachingObjectiveUiState()
        relativeOpportunityUi = RelativeOpportunityUiState()
        coachingSessionKey = null
        startInForeground()
        val trackId = intent.getStringExtra(EXTRA_TRACK_ID) ?: TimingTracks.BuckmorePark.id
        val track = TimingTracks.find(trackId) ?: TimingTracks.BuckmorePark
        val nextRepository =
          when (track.id) {
            TimingTracks.DaytonaSandownParkGp.id -> ClubspeedTimingRepository()
            TimingTracks.TeamSportFarnborough.id,
            TimingTracks.TeamSportLeicester.id -> TeamSportTimingRepository.forTrack(track)
            else -> AlphaRaceHubRepository()
          }
        configureRepository(nextRepository, connect = true, trackId = track.id)
        syncVoiceCommandListening()
      }
      ACTION_START_DEMO -> startDemo()
      ACTION_REPLAY_RECORDED_SESSION ->
        intent.getStringExtra(EXTRA_RECORDED_SESSION_ID)?.let(::startRecordedReplay)
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
      ACTION_SET_LISTEN_FOR_COMMANDS -> {
        TimingServiceState.mutableState.value =
          TimingServiceState.mutableState.value.copy(
            listenForCommands = intent.getBooleanExtra(EXTRA_LISTEN_FOR_COMMANDS, false),
          )
        syncVoiceCommandListening()
      }
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
    voiceCommandListener.release()
    commandAcknowledgement?.release()
    textToSpeech?.shutdown()
    scope.cancel()
    super.onDestroy()
  }

  private fun startInForeground() {
    val notification = streamNotification(TimingUiState(status = ConnectionStatus.Connecting))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val types =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && voiceCommandListening) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
          } else {
            0
          }
      startForeground(NOTIFICATION_ID, notification, types)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    foreground = true
  }

  private fun syncVoiceCommandListening() {
    val requested = TimingServiceState.mutableState.value.listenForCommands
    val hasPermission =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val canAttemptListening = requested && hasPermission
    // Declare microphone foreground-service ownership before opening the recognizer.
    voiceCommandListening = canAttemptListening
    if (foreground) startInForeground()
    voiceCommandListening = canAttemptListening && voiceCommandListener.start()
    if (!voiceCommandListening) {
      voiceCommandListener.stop()
      if (foreground) startInForeground()
    }
    if (requested && !voiceCommandListening) {
      Log.w(TAG, "Voice commands requested but speech recognition is unavailable")
      TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(listenForCommands = false)
    }
  }

  private fun handleVoiceCommand(command: RaceVoiceCommand) {
    Log.i(TAG, "Voice command recognised: $command")
    commandAcknowledgement?.startTone(ToneGenerator.TONE_PROP_ACK, COMMAND_ACK_DURATION_MS)
    val sections =
      when (command) {
        RaceVoiceCommand.Help -> listOf(VOICE_COMMAND_HELP_RESPONSE)
        RaceVoiceCommand.Status -> formatRaceStatusVoiceCommandSections(TimingServiceState.mutableState.value)
        RaceVoiceCommand.Gaps -> formatGapVoiceCommandSections(TimingServiceState.mutableState.value)
        RaceVoiceCommand.SpeakMore -> changeCoachingDetail(CoachingDetailDirection.More)
        RaceVoiceCommand.SpeakLess -> changeCoachingDetail(CoachingDetailDirection.Less)
        RaceVoiceCommand.SectorsOn -> setSectorTimingAnnouncements(enabled = true)
        RaceVoiceCommand.SectorsOff -> setSectorTimingAnnouncements(enabled = false)
        RaceVoiceCommand.VolumeUp -> adjustMediaVolume(1)
        RaceVoiceCommand.VolumeDown -> adjustMediaVolume(-1)
        is RaceVoiceCommand.Volume -> setMediaVolume(command.level)
      }
    announce(sections, preSpeechDelayMs = COMMAND_ACK_SPEECH_DELAY_MS)
  }

  private fun setMediaVolume(level: Int): List<String> {
    val audioManager = getSystemService(AudioManager::class.java)
    if (audioManager == null || audioManager.isVolumeFixed) return listOf("Volume cannot be changed on this device")
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return listOf("Volume cannot be changed on this device")
    val streamLevel = mediaVolumeStep(level, maxVolume)
    return try {
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamLevel, 0)
      val actualLevel = mediaVolumeLevel(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), maxVolume)
      listOf("Volume $actualLevel")
    } catch (error: SecurityException) {
      Log.w(TAG, "Unable to change media volume", error)
      listOf("Volume cannot be changed on this device")
    }
  }

  private fun adjustMediaVolume(direction: Int): List<String> {
    val audioManager = getSystemService(AudioManager::class.java)
    if (audioManager == null || audioManager.isVolumeFixed) return listOf("Volume cannot be changed on this device")
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return listOf("Volume cannot be changed on this device")
    val currentLevel = mediaVolumeLevel(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), maxVolume)
    return setMediaVolume((currentLevel + direction).coerceIn(0, 10))
  }

  private fun setSectorTimingAnnouncements(enabled: Boolean): List<String> {
    val state = TimingServiceState.mutableState.value
    TimingServiceState.mutableState.value =
      state.copy(
        announcementSettings = state.announcementSettings.copy(speakSectorDeltas = enabled),
      )
    getSharedPreferences(TIMING_PREFERENCES_NAME, MODE_PRIVATE)
      .edit()
      .putBoolean(PREF_SPEAK_SECTOR_DELTAS, enabled)
      .apply()
    return listOf("Sector times ${if (enabled) "on" else "off"}")
  }

  private fun changeCoachingDetail(direction: CoachingDetailDirection): List<String> {
    val state = TimingServiceState.mutableState.value
    val next = adjustCoachingDetail(state.announcementSettings.coachingChattiness, direction)
    TimingServiceState.mutableState.value =
      state.copy(
        announcementSettings = state.announcementSettings.copy(coachingChattiness = next),
      )
    getSharedPreferences(TIMING_PREFERENCES_NAME, MODE_PRIVATE)
      .edit()
      .putString(PREF_COACHING_CHATTINESS, next.name)
      .apply()
    return listOf(formatCoachingDetailConfirmation(next))
  }

  private fun observeCompletedLaps(state: com.example.lapbot.data.TimingUiState) {
    val selectedKartNumber = state.selectedKartNumber
    val selectionChanged = observedSectorKartNumber != selectedKartNumber
    if (selectionChanged) {
      observedSectorKartNumber = selectedKartNumber
      observedSectorUpdates.clear()
      gapAnnouncementJob?.cancel()
      gapAnnouncementJob = null
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
          val coaching =
            sessionCoach.onLap(driver, state.rows, latestCompletedLap, state.announcementSettings.coachingChattiness)
          coachingObjectiveUi = coaching.objectiveUi
          relativeOpportunityUi = coaching.relativeOpportunityUi
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
        if (isNewSelectedLap) sessionCoach.onLap(driver, state.rows, completedLap, state.announcementSettings.coachingChattiness)
        else CoachingResult(
          objectiveUi = sessionCoach.objectiveUi(driver.id),
          relativeOpportunityUi = sessionCoach.relativeOpportunityUi(),
        )
      if (isNewSelectedLap) {
        listOfNotNull(finalSectorCoaching, lapCoaching.lapObservation)
          .distinctBy(CoachingObservation::text)
          .forEach { observation ->
            scope.launch { sessionRecorder.recordObservation(observation.text, completedLap, observation.sector) }
          }
      }
      if (isSelected) {
        coachingObjectiveUi = lapCoaching.objectiveUi
        relativeOpportunityUi = lapCoaching.relativeOpportunityUi
      }
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
        val gaps =
          if (state.supportsGaps && state.announcementSettings.speakGaps) {
            calculateAdjacentRaceGaps(state.rows, driver, completedLap)
          } else null
        val gapSections =
          if (gaps?.isComplete == true) {
            formatGapAnnouncementSections(gaps, state.announcementSettings.speakGapKartNumbers)
          } else emptyList()
        if (state.supportsGaps && state.announcementSettings.speakGaps && gaps?.isComplete != true) {
          scheduleGapAnnouncement(driver.id, completedLap, state.sessionKey)
        } else {
          gapAnnouncementJob?.cancel()
          gapAnnouncementJob = null
        }
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
            gapSections = gapSections,
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
    preSpeechDelayMs: Long = 0,
  ) {
    if (sections.isEmpty()) return
    scope.launch { sessionRecorder.recordAnnouncement(sections, lap) }
    announcementQueue.enqueue(
      PendingAnnouncement(sections, lap, stopServiceAfter, sectorSound, preSpeechDelayMs),
    )
    voiceCommandListener.pauseForPlayback()
    drainAnnouncementQueue()
  }

  private fun drainAnnouncementQueue() {
    if (!textToSpeechReady || activeAnnouncement != null || announcementDelayJob != null || tonesPlaying) return
    if (pendingToneSpeechComplete && playPendingTones(TimingServiceState.mutableState.value)) return
    val next = announcementQueue.startNext()
    if (next == null) {
      voiceCommandListener.resumeAfterPlayback()
      if (stopWhenAudioQueueDrains && !foreground) {
        stopWhenAudioQueueDrains = false
        stopSelf()
      }
      return
    }
    if (next.stopServiceAfter) stopWhenAudioQueueDrains = true
    voiceCommandListener.pauseForPlayback()
    next.sectorSound?.let(sectorSoundPlayer::play)
    val delayBeforeSpeech =
      maxOf(next.preSpeechDelayMs, if (next.sectorSound != null) SECTOR_SPEECH_DELAY_MS else 0)
    if (delayBeforeSpeech > 0) {
      announcementDelayJob =
        scope.launch {
          delay(delayBeforeSpeech)
          announcementDelayJob = null
          speakActiveAnnouncement()
        }
    } else {
      speakActiveAnnouncement()
    }
  }

  private fun speakActiveAnnouncement() {
    val announcement = activeAnnouncement ?: return
    val tts = textToSpeech ?: return
    configureVoice(tts, TimingServiceState.mutableState.value.announcementSettings.voiceGender)
    tts.setSpeechRate(TimingServiceState.mutableState.value.announcementSettings.speechRate)
    tts.setPitch(NATURAL_SPEECH_PITCH)
    val utteranceId = "announcement-${System.nanoTime()}"
    activeUtteranceId = utteranceId
    val result = tts.speak(
      announcement.sections.joinToString(separator = ". ", postfix = "."),
      TextToSpeech.QUEUE_ADD,
      null,
      utteranceId,
    )
    if (result == TextToSpeech.ERROR) completeAnnouncement(utteranceId, succeeded = false)
  }

  private fun completeAnnouncement(utteranceId: String, succeeded: Boolean) {
    if (activeUtteranceId != utteranceId) return
    val completed = activeAnnouncement
    activeUtteranceId = null
    announcementQueue.completeActive()
    if (succeeded && completed?.lap != null && pendingToneLap == completed.lap) {
      pendingToneSpeechComplete = true
    } else if (!succeeded) {
      clearPendingTone(completed?.lap)
    }
    drainAnnouncementQueue()
  }

  private fun scheduleGapAnnouncement(driverId: String, completedLap: Int, sessionKey: String?) {
    gapAnnouncementJob?.cancel()
    gapAnnouncementJob =
      scope.launch {
        // Allow the following kart to cross the line and the lap call/tones to finish first.
        delay(GAP_COHERENCE_DELAY_MS)
        val state = TimingServiceState.mutableState.value
        if (
          state.status != ConnectionStatus.Connected ||
            !state.coachEnabled ||
            !state.supportsGaps ||
            !state.announcementSettings.speakGaps ||
            state.sessionKey != sessionKey
        ) return@launch
        val selected =
          state.rows.firstOrNull {
            it.id == driverId &&
              it.lap == completedLap &&
              canonicalKartNumber(it.number) == state.selectedKartNumber
          } ?: return@launch
        val sections =
          formatGapAnnouncementSections(
            calculateAdjacentRaceGaps(state.rows, selected, completedLap),
            includeKartNumbers = state.announcementSettings.speakGapKartNumbers,
          )
        if (sections.isNotEmpty()) announce(sections = sections)
        gapAnnouncementJob = null
      }
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
        coaching.sectorObservation?.let { observation ->
          scope.launch { sessionRecorder.recordObservation(observation.text, lap, observation.sector) }
        }
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
    val state = TimingServiceState.mutableState.value
    announce(
      sections =
        buildList {
          add("Great lap")
          add("52 point 34")
          add("Point 22 quicker than last")
          if (state.supportsGaps && state.announcementSettings.speakGaps) {
            if (state.announcementSettings.speakGapKartNumbers) {
              add("Gap to P3, kart 12, 1 point 20")
              add("Gap to P5, kart 27, point 33")
            } else {
              add("Gap to P3, 1 point 20")
              add("Gap to P5, point 33")
            }
          }
          add("Biggest gain, sector two, point 18")
        },
      stopServiceAfter = !foreground,
    )
  }

  private fun playPendingTones(state: com.example.lapbot.data.TimingUiState): Boolean {
    if (activeAnnouncement != null || announcementDelayJob != null || tonesPlaying) return false
    val lap = pendingToneLap ?: return false
    if (!pendingToneSpeechComplete) return false
    if (!state.toneSettings.enabled) {
      pendingToneLap = null
      pendingToneSpeechComplete = false
      return false
    }
    val sequence =
      buildToneSequence(state, lap)
        ?: run {
          pendingToneLap = null
          pendingToneSpeechComplete = false
          return false
        }
    pendingToneLap = null
    pendingToneSpeechComplete = false
    tonesPlaying = true
    voiceCommandListener.pauseForPlayback()
    tonePlayer.play(sequence) {
      tonesPlaying = false
      drainAnnouncementQueue()
    }
    return true
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
    } else if (activeAnnouncement != null || announcementDelayJob != null || tonesPlaying || !announcementQueue.isEmpty) {
      Log.i(TAG, "Test tones skipped while the audio queue is active")
    } else {
      tonesPlaying = true
      voiceCommandListener.pauseForPlayback()
      tonePlayer.play(sequence) {
        tonesPlaying = false
        drainAnnouncementQueue()
      }
    }
  }

  private fun startDemo() {
    Log.i(TAG, "Starting demo replay for session 837888")
    clearAudioQueue()
    demoActive = true
    demoJob?.cancel()
    gapAnnouncementJob?.cancel()
    repository?.disconnect()
    observedCompletedLaps.clear()
    knownDriverIds.clear()
    observedSectorUpdates.clear()
    observedSectorKartNumber = null
    sessionCoach.reset()
    coachingObjectiveUi = CoachingObjectiveUiState()
    relativeOpportunityUi = RelativeOpportunityUiState()
    coachingSessionKey = "demo:837888"
    pendingToneLap = null
    pendingToneSpeechComplete = false
    startInForeground()

    val replay = DemoTimingReplay()
    val settings = TimingServiceState.mutableState.value
    val initial =
      settings.copy(
        status = ConnectionStatus.Connected,
        isDemo = true,
        replayDescription = "Session 837888 · kart #5 John Reeves · replayed at 2× speed",
        selectedTrackId = TimingTracks.BuckmorePark.id,
        sessionKey = coachingSessionKey,
        supportsSectors = true,
        supportsGaps = true,
        rows = replay.initialRows(),
        jsonTail = emptyList(),
        selectedKartNumber =
          if (settings.autoDetectDriverName != null) {
            findDriverByNameFragment(replay.initialRows(), settings.autoDetectDriverName)?.number?.let(::canonicalKartNumber)
          } else {
            "5"
          },
        error = null,
      )
    observeCompletedLaps(initial)
    TimingServiceState.mutableState.value =
      initial.copy(coachingObjective = coachingObjectiveUi, relativeOpportunity = relativeOpportunityUi)
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
          TimingServiceState.mutableState.value =
            state.copy(coachingObjective = coachingObjectiveUi, relativeOpportunity = relativeOpportunityUi)
          playPendingTones(state)
          if (foreground) notificationManager.notify(NOTIFICATION_ID, streamNotification(state))
          delay(frame.nextDelayMs)
        }
      }
  }

  private fun startRecordedReplay(sessionId: String) {
    clearAudioQueue()
    demoActive = true
    demoJob?.cancel()
    gapAnnouncementJob?.cancel()
    repository?.disconnect()
    startInForeground()
    demoJob =
      scope.launch {
        sessionRecorder.finish()
        val recorded = sessionStore.load(sessionId)
        if (recorded == null) {
          TimingServiceState.mutableState.value =
            TimingServiceState.mutableState.value.copy(
              status = ConnectionStatus.Disconnected,
              isDemo = false,
              replayDescription = null,
              error = "Recorded session is unavailable.",
            )
          return@launch
        }
        observedCompletedLaps.clear()
        knownDriverIds.clear()
        observedSectorUpdates.clear()
        observedSectorKartNumber = null
        sessionCoach.reset()
        coachingObjectiveUi = CoachingObjectiveUiState()
        relativeOpportunityUi = RelativeOpportunityUiState()
        coachingSessionKey = "recorded:${recorded.id}"
        pendingToneLap = null
        pendingToneSpeechComplete = false
        val track = TimingTracks.find(recorded.trackId)
        val focusedDriver =
          com.example.lapbot.data.RecordedDriver(
            id = recorded.selectedDriverId,
            name = recorded.selectedDriverName,
            kartNumber = recorded.kartNumbers.lastOrNull().orEmpty(),
            finalPosition = recorded.laps.lastOrNull()?.position,
            laps = recorded.laps,
          )
        val replayField = recorded.field.filterNot { it.id == recorded.selectedDriverId } + focusedDriver
        val replayLaps = recorded.laps.map { it.lap }.sorted()
        replayLaps.forEachIndexed { index, lap ->
          if (!demoActive) return@launch
          val recordedLap = recorded.laps.first { it.lap == lap }
          val initialRows = replayField.map { it.toTimingRow(lap) }
          val focusedPosition = recordedLap.position
          val rows =
            initialRows.map { row ->
              if (
                focusedPosition != null && row.position == focusedPosition + 1 &&
                  recordedLap.gapBehindMs != null
              ) {
                row.copy(gapToAheadMs = recordedLap.gapBehindMs, gapRecordedAtLap = lap)
              } else row
            }
          val state =
            TimingServiceState.mutableState.value.copy(
              status = ConnectionStatus.Connected,
              isDemo = true,
              replayDescription = "${recorded.venue} · ${recorded.selectedDriverName} · recorded replay",
              selectedTrackId = recorded.trackId,
              sessionKey = coachingSessionKey,
              supportsSectors = track?.supportsSectors ?: recorded.laps.any { it.sector1Ms != null },
              supportsGaps = track?.supportsGaps ?: true,
              rows = rows,
              selectedKartNumber = focusedDriver.kartNumber,
              error = null,
            )
          observeCompletedLaps(state)
          TimingServiceState.mutableState.value =
            state.copy(coachingObjective = coachingObjectiveUi, relativeOpportunity = relativeOpportunityUi)
          if (foreground) notificationManager.notify(NOTIFICATION_ID, streamNotification(state))
          if (index < replayLaps.lastIndex) delay(RECORDED_REPLAY_LAP_DELAY_MS)
        }
      }
  }

  private fun clearAudioQueue() {
    announcementQueue.clear()
    announcementDelayJob?.cancel()
    announcementDelayJob = null
    activeUtteranceId = null
    stopWhenAudioQueueDrains = false
    pendingToneLap = null
    pendingToneSpeechComplete = false
    tonesPlaying = false
    textToSpeech?.stop()
    tonePlayer.stop()
    sectorSoundPlayer.release()
  }

  private fun stopStreaming() {
    clearAudioQueue()
    demoActive = false
    demoJob?.cancel()
    gapAnnouncementJob?.cancel()
    repository?.disconnect()
    observedSectorUpdates.clear()
    observedSectorKartNumber = null
    sessionCoach.reset()
    coachingObjectiveUi = CoachingObjectiveUiState()
    relativeOpportunityUi = RelativeOpportunityUiState()
    coachingSessionKey = null
    voiceCommandListener.stop()
    voiceCommandListening = false
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        status = ConnectionStatus.Disconnected,
        isDemo = false,
        replayDescription = null,
        selectedTrackId = null,
        supportsSectors = true,
        supportsGaps = true,
        error = null,
      )
    scope.launch {
      sessionRecorder.finish()
      stopForeground(STOP_FOREGROUND_REMOVE)
      foreground = false
      stopSelf()
    }
  }

  private fun streamNotification(state: TimingUiState): Notification =
    NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(if (state.isDemo) "Lapbot demo replay" else "Lapbot live timing")
      .setContentText(if (state.isDemo) state.replayDescription ?: "Replaying recorded timing" else state.status.notificationText)
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
    const val ACTION_SET_LISTEN_FOR_COMMANDS = "com.example.lapbot.action.SET_LISTEN_FOR_COMMANDS"
    const val ACTION_REPLAY_RECORDED_SESSION = "com.example.lapbot.action.REPLAY_RECORDED_SESSION"
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
    const val EXTRA_LISTEN_FOR_COMMANDS = "listenForCommands"
    const val EXTRA_RECORDED_SESSION_ID = "recordedSessionId"
    private const val DEFAULT_TAIL_LIMIT = 20
    private const val CHANNEL_ID = "live_timing"
    private const val NOTIFICATION_ID = 1001
    private const val NATURAL_SPEECH_PITCH = 0.98f
    private const val SECTOR_SPEECH_DELAY_MS = 1_000L
    private const val GAP_COHERENCE_DELAY_MS = 5_000L
    private const val COMMAND_ACK_DURATION_MS = 150
    private const val COMMAND_ACK_SPEECH_DELAY_MS = 220L
    private const val RECORDED_REPLAY_LAP_DELAY_MS = 1_000L
  }
}

private data class PendingAnnouncement(
  val sections: List<String>,
  val lap: Int?,
  val stopServiceAfter: Boolean,
  val sectorSound: SectorSound?,
  val preSpeechDelayMs: Long,
)

private data class SectorUpdateKey(val driverId: String, val lap: Int, val sector: Int)

internal enum class CoachingDetailDirection {
  More,
  Less,
}

internal fun adjustCoachingDetail(
  current: CoachingChattiness,
  direction: CoachingDetailDirection,
): CoachingChattiness =
  when (direction) {
    CoachingDetailDirection.More ->
      when (current) {
        CoachingChattiness.Low -> CoachingChattiness.Medium
        CoachingChattiness.Medium,
        CoachingChattiness.High,
        -> CoachingChattiness.High
      }
    CoachingDetailDirection.Less ->
      when (current) {
        CoachingChattiness.High -> CoachingChattiness.Medium
        CoachingChattiness.Medium,
        CoachingChattiness.Low,
        -> CoachingChattiness.Low
      }
  }

internal fun formatCoachingDetailConfirmation(detail: CoachingChattiness): String =
  "Coaching detail, ${if (detail == CoachingChattiness.Medium) "mid" else detail.name.lowercase()}"

internal fun mediaVolumeStep(level: Int, maxVolume: Int): Int {
  require(level in 0..10 && maxVolume > 0)
  return if (level == 0) 0 else ((level * maxVolume + 5) / 10).coerceAtLeast(1)
}

internal fun mediaVolumeLevel(streamLevel: Int, maxVolume: Int): Int {
  require(maxVolume > 0)
  return ((streamLevel * 10 + maxVolume / 2) / maxVolume).coerceIn(0, 10)
}

internal fun formatGapVoiceCommandSections(state: TimingUiState): List<String> {
  if (state.status != ConnectionStatus.Connected) return listOf("Live timing is not connected")
  if (!state.supportsGaps) return listOf("Gap information is not available for this track")
  val selectedKartNumber = canonicalKartNumber(state.selectedKartNumber)
    ?: return listOf("Pick a driver in focus first")
  val selected =
    state.rows.firstOrNull { canonicalKartNumber(it.number) == selectedKartNumber }
      ?: return listOf("The driver in focus is not in the current session")
  val completedLap = selected.gapRecordedAtLap ?: selected.recentCompletedLap ?: selected.lap
    ?: return listOf("Gaps are not available yet")
  val sections =
    formatGapAnnouncementSections(
      calculateAdjacentRaceGaps(state.rows, selected, completedLap),
      includeKartNumbers = state.announcementSettings.speakGapKartNumbers,
    )
  return sections.ifEmpty { listOf("Gaps are not available yet") }
}

internal fun formatRaceStatusVoiceCommandSections(state: TimingUiState): List<String> {
  if (state.status != ConnectionStatus.Connected) return listOf("Live timing is not connected")
  val selectedKartNumber = canonicalKartNumber(state.selectedKartNumber)
    ?: return listOf("Pick a driver in focus first")
  val selected =
    state.rows.firstOrNull { canonicalKartNumber(it.number) == selectedKartNumber }
      ?: return listOf("The driver in focus is not in the current session")
  val position = selected.position ?: return listOf("Position is not available yet")
  val sections = mutableListOf("Position P$position")
  if (!state.supportsGaps) return sections + "Gap information is not available for this track"
  val completedLap = selected.gapRecordedAtLap ?: selected.recentCompletedLap ?: selected.lap
  val gaps = completedLap?.let { calculateAdjacentRaceGaps(state.rows, selected, it) }
  sections += formatGapAnnouncementSections(gaps, includeKartNumbers = true)
  if (sections.size == 1) sections += "Gaps are not available yet"
  return sections
}

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
  gapSections: List<String> = emptyList(),
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
    }
    addAll(gapSections)
    coaching?.let(::add)
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
