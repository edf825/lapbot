package com.example.lapbot.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.lapbot.service.TimingStreamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object TimingServiceState {
  val mutableState = MutableStateFlow(TimingUiState())
  var running: Boolean = false
}

class TimingServiceRepository(context: Context) : TimingRepository {
  private val applicationContext = context.applicationContext
  private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  override val state: StateFlow<TimingUiState> = TimingServiceState.mutableState

  init {
    val autoDetectDriverName = canonicalDriverNameFragment(preferences.getString(PREF_AUTO_DETECT_DRIVER_NAME, null))
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, true),
        coachEnabled = preferences.getBoolean(PREF_COACH_ENABLED, true),
        listenForCommands = preferences.getBoolean(PREF_LISTEN_FOR_COMMANDS, false),
        autoDetectDriverName = autoDetectDriverName,
        selectedKartNumber =
          if (autoDetectDriverName != null) {
            findDriverByNameFragment(TimingServiceState.mutableState.value.rows, autoDetectDriverName)
              ?.number?.let(::canonicalKartNumber)
          } else {
            TimingServiceState.mutableState.value.selectedKartNumber
          },
        announcementSettings = loadAnnouncementSettings(),
        toneSettings =
          TimingServiceState.mutableState.value.toneSettings.copy(
            enabled = preferences.getBoolean(PREF_TONES_ENABLED, true),
          ),
      )
  }

  override fun connect(trackId: String) {
    ContextCompat.startForegroundService(
      applicationContext,
      serviceIntent(TimingStreamService.ACTION_CONNECT)
        .putExtra(TimingStreamService.EXTRA_TRACK_ID, trackId),
    )
  }

  override fun startDemo() {
    ContextCompat.startForegroundService(
      applicationContext,
      serviceIntent(TimingStreamService.ACTION_START_DEMO),
    )
  }

  override fun disconnect() {
    if (TimingServiceState.running) {
      applicationContext.startService(serviceIntent(TimingStreamService.ACTION_DISCONNECT))
    } else {
      TimingServiceState.mutableState.value =
        TimingServiceState.mutableState.value.copy(
          status = ConnectionStatus.Disconnected,
          isDemo = false,
          selectedTrackId = null,
          supportsSectors = true,
          supportsGaps = true,
          error = null,
        )
    }
  }

  override fun setAutoReconnect(enabled: Boolean) {
    TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(autoReconnect = enabled)
    preferences.edit().putBoolean(PREF_AUTO_RECONNECT, enabled).apply()
    if (TimingServiceState.running) {
      applicationContext.startService(
        serviceIntent(TimingStreamService.ACTION_SET_AUTO_RECONNECT)
          .putExtra(TimingStreamService.EXTRA_AUTO_RECONNECT, enabled),
      )
    }
  }

  override fun setTailLimit(limit: Int) {
    val boundedLimit = limit.coerceIn(5, 100)
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        tailLimit = boundedLimit,
        jsonTail = TimingServiceState.mutableState.value.jsonTail.takeLast(boundedLimit),
      )
    if (TimingServiceState.running) {
      applicationContext.startService(
        serviceIntent(TimingStreamService.ACTION_SET_TAIL_LIMIT)
          .putExtra(TimingStreamService.EXTRA_TAIL_LIMIT, boundedLimit),
      )
    }
  }

  override fun setReconnectPolicy(policy: ReconnectPolicy) {
    TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(reconnectPolicy = policy)
    if (TimingServiceState.running) {
      applicationContext.startService(
        serviceIntent(TimingStreamService.ACTION_SET_RECONNECT_POLICY)
          .putExtra(TimingStreamService.EXTRA_INITIAL_DELAY_MS, policy.initialDelayMs)
          .putExtra(TimingStreamService.EXTRA_MAX_DELAY_MS, policy.maxDelayMs)
          .putExtra(TimingStreamService.EXTRA_GIVE_UP_AFTER_MS, policy.giveUpAfterMs),
      )
    }
  }

  override fun setSelectedKartNumber(kartNumber: String?) {
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        selectedKartNumber = canonicalKartNumber(kartNumber),
        autoDetectDriverName = null,
      )
    preferences.edit().remove(PREF_AUTO_DETECT_DRIVER_NAME).apply()
  }

  override fun setAutoDetectDriverName(nameFragment: String?) {
    val canonical = canonicalDriverNameFragment(nameFragment)
    val matchedKart =
      findDriverByNameFragment(TimingServiceState.mutableState.value.rows, canonical)
        ?.number?.let(::canonicalKartNumber)
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        autoDetectDriverName = canonical,
        selectedKartNumber = matchedKart,
      )
    preferences.edit().apply {
      if (canonical == null) remove(PREF_AUTO_DETECT_DRIVER_NAME)
      else putString(PREF_AUTO_DETECT_DRIVER_NAME, canonical)
    }.apply()
  }

  override fun setMetricsSinceLap(lap: Int?) {
    TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(metricsSinceLap = lap)
  }

  override fun setCoachEnabled(enabled: Boolean) {
    TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(coachEnabled = enabled)
    preferences.edit().putBoolean(PREF_COACH_ENABLED, enabled).apply()
  }

  override fun setListenForCommands(enabled: Boolean) {
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(listenForCommands = enabled)
    preferences.edit().putBoolean(PREF_LISTEN_FOR_COMMANDS, enabled).apply()
    if (TimingServiceState.running) {
      applicationContext.startService(
        serviceIntent(TimingStreamService.ACTION_SET_LISTEN_FOR_COMMANDS)
          .putExtra(TimingStreamService.EXTRA_LISTEN_FOR_COMMANDS, enabled),
      )
    }
  }

  override fun setAnnouncementSettings(settings: AnnouncementSettings) {
    val bounded = settings.copy(speechRate = settings.speechRate.coerceIn(0.8f, 1.1f))
    TimingServiceState.mutableState.value =
      TimingServiceState.mutableState.value.copy(
        announcementSettings = bounded,
      )
    preferences.edit()
      .putBoolean(PREF_SPEAK_LAST, bounded.speakLastComparison)
      .putBoolean(PREF_SPEAK_BEST, bounded.speakBestComparison)
      .putBoolean(PREF_SPEAK_GAPS, bounded.speakGaps)
      .putBoolean(PREF_SPEAK_GAP_KART_NUMBERS, bounded.speakGapKartNumbers)
      .putBoolean(PREF_SPEAK_SECTOR_DELTAS, bounded.speakSectorDeltas)
      .putBoolean(PREF_SECTOR_TONES_ENABLED, bounded.sectorTonesEnabled)
      .putBoolean(PREF_SPEAK_COACHING, bounded.speakCoaching)
      .putString(PREF_COACHING_CHATTINESS, bounded.coachingChattiness.name)
      .putString(PREF_VOICE_GENDER, bounded.voiceGender.name)
      .putFloat(PREF_SPEECH_RATE, bounded.speechRate)
      .apply()
  }

  override fun previewAnnouncement() {
    applicationContext.startService(serviceIntent(TimingStreamService.ACTION_PREVIEW_ANNOUNCEMENT))
  }

  override fun setToneSettings(settings: ToneSettings) {
    val bounded =
      settings.copy(
        referenceDurationMs = settings.referenceDurationMs.coerceIn(50, 1_000),
        lapDurationMs = settings.lapDurationMs.coerceIn(50, 1_000),
        sectorDurationMs = settings.sectorDurationMs.coerceIn(50, 1_000),
      )
    TimingServiceState.mutableState.value = TimingServiceState.mutableState.value.copy(toneSettings = bounded)
    preferences.edit().putBoolean(PREF_TONES_ENABLED, bounded.enabled).apply()
    if (TimingServiceState.running) {
      applicationContext.startService(
        serviceIntent(TimingStreamService.ACTION_SET_TONE_SETTINGS)
          .putExtra(TimingStreamService.EXTRA_TONES_ENABLED, bounded.enabled)
          .putExtra(TimingStreamService.EXTRA_TONE_METRIC, bounded.metric.name)
          .putExtra(TimingStreamService.EXTRA_REFERENCE_DURATION_MS, bounded.referenceDurationMs)
          .putExtra(TimingStreamService.EXTRA_LAP_DURATION_MS, bounded.lapDurationMs)
          .putExtra(TimingStreamService.EXTRA_SECTOR_DURATION_MS, bounded.sectorDurationMs),
      )
    }
  }

  override fun playTestTones() {
    if (TimingServiceState.running) {
      applicationContext.startService(serviceIntent(TimingStreamService.ACTION_PLAY_TEST_TONES))
    }
  }

  override fun close() = Unit

  private fun serviceIntent(action: String) =
    Intent(applicationContext, TimingStreamService::class.java).setAction(action)

  private fun loadAnnouncementSettings(): AnnouncementSettings {
    val defaults = AnnouncementSettings()
    return AnnouncementSettings(
      speakLastComparison = preferences.getBoolean(PREF_SPEAK_LAST, defaults.speakLastComparison),
      speakBestComparison = preferences.getBoolean(PREF_SPEAK_BEST, defaults.speakBestComparison),
      speakGaps = preferences.getBoolean(PREF_SPEAK_GAPS, defaults.speakGaps),
      speakGapKartNumbers =
        preferences.getBoolean(PREF_SPEAK_GAP_KART_NUMBERS, defaults.speakGapKartNumbers),
      speakSectorDeltas = preferences.getBoolean(PREF_SPEAK_SECTOR_DELTAS, defaults.speakSectorDeltas),
      sectorTonesEnabled = preferences.getBoolean(PREF_SECTOR_TONES_ENABLED, defaults.sectorTonesEnabled),
      speakCoaching = preferences.getBoolean(PREF_SPEAK_COACHING, defaults.speakCoaching),
      coachingChattiness =
        preferences.getString(PREF_COACHING_CHATTINESS, null)?.let { name ->
          runCatching { CoachingChattiness.valueOf(name) }.getOrNull()
        } ?: defaults.coachingChattiness,
      voiceGender =
        preferences.getString(PREF_VOICE_GENDER, null)?.let { name ->
          runCatching { AnnouncementVoiceGender.valueOf(name) }.getOrNull()
        } ?: defaults.voiceGender,
      speechRate = preferences.getFloat(PREF_SPEECH_RATE, defaults.speechRate).coerceIn(0.8f, 1.1f),
    )
  }

  private companion object {
    const val PREFERENCES_NAME = "announcements"
    const val PREF_AUTO_RECONNECT = "autoReconnect"
    const val PREF_COACH_ENABLED = "coachEnabled"
    const val PREF_LISTEN_FOR_COMMANDS = "listenForCommands"
    const val PREF_AUTO_DETECT_DRIVER_NAME = "autoDetectDriverName"
    const val PREF_TONES_ENABLED = "tonesEnabled"
    const val PREF_SPEAK_LAST = "speakLastComparison"
    const val PREF_SPEAK_BEST = "speakBestComparison"
    const val PREF_SPEAK_GAPS = "speakGaps"
    const val PREF_SPEAK_GAP_KART_NUMBERS = "speakGapKartNumbers"
    const val PREF_SPEAK_SECTOR_DELTAS = "speakSectorDeltas"
    const val PREF_SECTOR_TONES_ENABLED = "sectorTonesEnabled"
    const val PREF_SPEAK_COACHING = "speakCoaching"
    const val PREF_COACHING_CHATTINESS = "coachingChattiness"
    const val PREF_VOICE_GENDER = "voiceGender"
    const val PREF_SPEECH_RATE = "speechRate"
  }
}
