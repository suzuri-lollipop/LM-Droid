package com.suzuri.lmdroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton that tracks the current status and last recognized text of [WakeWordService],
 * allowing the Settings screen to display debug info without being bound to the service.
 */
object WakeWordDebugManager {
    private val _status = MutableStateFlow<WakeWordStatus>(WakeWordStatus.Idle)
    val status: StateFlow<WakeWordStatus> = _status.asStateFlow()

    private val _lastResult = MutableStateFlow<String>("")
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    // Live normalized peak amplitude (0f..1f) of the wake-word listener's mic input, published
    // every capture buffer so Settings can render a Discord-style level meter without opening a
    // second, competing AudioRecord session of its own.
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    fun updateStatus(newStatus: WakeWordStatus) {
        _status.value = newStatus
    }

    fun updateLastResult(text: String) {
        _lastResult.value = text
    }

    fun updateMicLevel(level: Float) {
        _micLevel.value = level
    }
}

sealed class WakeWordStatus {
    object Idle : WakeWordStatus()
    object LoadingModel : WakeWordStatus()
    object Listening : WakeWordStatus()
    object Paused : WakeWordStatus()
    data class Error(val message: String) : WakeWordStatus()
}
