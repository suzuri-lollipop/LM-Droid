package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.SystemPromptEntity
import com.suzuri.lmdroid.data.repository.SystemPromptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SystemPromptListViewModel(
    private val systemPromptRepository: SystemPromptRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemPromptListUiState())
    val uiState: StateFlow<SystemPromptListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                systemPromptRepository.observePrompts(),
                systemPromptRepository.selectedPromptIds,
            ) { prompts, selectedIds -> prompts.map { it.toUiModel(selectedIds) } }
                .collect { prompts -> _uiState.update { it.copy(prompts = prompts) } }
        }
    }

    /** Creates the prompt row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createPrompt(name: String): Long = systemPromptRepository.createPrompt(name)

    /** Several prompts may be active simultaneously — toggling one leaves every other prompt's selection untouched. */
    fun onTogglePrompt(id: Long) {
        viewModelScope.launch { systemPromptRepository.togglePrompt(id) }
    }

    fun onDeletePrompt(id: Long) {
        viewModelScope.launch { systemPromptRepository.deletePrompt(id) }
    }

    private fun SystemPromptEntity.toUiModel(selectedIds: Set<Long>) =
        SystemPromptUiModel(id = id, name = name, content = content, isSelected = id in selectedIds)
}
