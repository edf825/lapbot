package com.example.lapbot

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object LiveTimings : NavKey

@Serializable data class Driver(val driverId: String) : NavKey

@Serializable data object RaceEngineer : NavKey

@Serializable data object EngineerSettings : NavKey

@Serializable data object PitlaneMode : NavKey

@Serializable data object Sessions : NavKey

@Serializable data class SessionDebrief(val sessionId: String) : NavKey
