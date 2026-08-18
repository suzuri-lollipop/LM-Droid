package com.suzuri.lmdroid.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.stt.SttCaptureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings → 開発者向け: developer-only controls for the local voice-input (STT) engine — the
 * Bluetooth routing strategy and the "dump captured audio" diagnostic. Kept out of the normal 音声
 * screen on purpose: these exist to investigate recognition issues, not for everyday use. The
 * capture file list is refreshed on demand ([onRefreshCaptures]) rather than live, since those
 * files only change when a voice-input session actually runs.
 */
class DeveloperSettingsViewModel(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeveloperSettingsUiState())
    val uiState: StateFlow<DeveloperSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.bluetoothRoutingMode.collect { mode ->
                _uiState.update { it.copy(bluetoothRoutingMode = mode) }
            }
        }
        viewModelScope.launch {
            settingsRepository.sttCaptureDebug.collect { enabled ->
                _uiState.update { it.copy(sttCaptureDebug = enabled) }
            }
        }
        onRefreshCaptures()
    }

    fun onBluetoothRoutingModeChanged(mode: String) {
        viewModelScope.launch { settingsRepository.setBluetoothRoutingMode(mode) }
    }

    fun onSttCaptureDebugChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSttCaptureDebug(enabled) }
    }

    fun onRefreshCaptures() {
        val files = SttCaptureStore.listFiles(context).map {
            SttCaptureFileUiModel(name = it.name, sizeBytes = it.length())
        }
        _uiState.update { it.copy(captureFiles = files) }
    }

    fun onDeleteAllCaptures() {
        SttCaptureStore.deleteAll(context)
        _uiState.update { it.copy(captureFiles = emptyList()) }
    }
}