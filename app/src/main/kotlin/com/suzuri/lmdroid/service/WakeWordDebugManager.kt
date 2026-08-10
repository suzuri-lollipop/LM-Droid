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

    fun updateStatus(newStatus: WakeWordStatus) {
        _status.value = newStatus
    }

    fun updateLastResult(text: String) {
        _lastResult.value = text
    }
}

sealed class WakeWordStatus {
    object Idle : WakeWordStatus()
    object LoadingModel : WakeWordStatus()
    object Listening : WakeWordStatus()
    object Paused : WakeWordStatus()
    data class Error(val message: String) : WakeWordStatus()
}
