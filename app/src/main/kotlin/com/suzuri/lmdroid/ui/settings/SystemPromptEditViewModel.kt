package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.repository.SystemPromptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits a single [com.suzuri.lmdroid.data.db.SystemPromptEntity], loaded on demand via
 * [loadPrompt] — like [SettingsViewModel], this instance is shared across every prompt edited
 * during the app's lifetime rather than recreated per navigation, but unlike it there's no
 * reactive per-id sub-flow to guard against racing (just a one-shot load), so a plain `var` id
 * guard is enough here.
 */
class SystemPromptEditViewModel(
    private val systemPromptRepository: SystemPromptRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemPromptEditUiState())
    val uiState: StateFlow<SystemPromptEditUiState> = _uiState.asStateFlow()

    private var promptId: Long? = null

    /** Called by the edit screen (e.g. from a LaunchedEffect keyed on the navigated-to prompt id). */
    fun loadPrompt(id: Long) {
        if (promptId == id) return
        promptId = id
        viewModelScope.launch {
            val prompt = systemPromptRepository.getPrompt(id) ?: return@launch
            _uiState.update { it.copy(name = prompt.name, content = prompt.content, saved = false) }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, saved = false) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, saved = false) }
    }

    fun onSave() {
        val id = promptId ?: return
        val state = _uiState.value
        viewModelScope.launch {
            systemPromptRepository.updatePrompt(id, state.name, state.content)
            _uiState.update { it.copy(saved = true) }
        }
    }
}
