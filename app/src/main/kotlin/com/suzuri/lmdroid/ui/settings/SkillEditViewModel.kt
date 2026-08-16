package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.repository.SkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits a single [com.suzuri.lmdroid.data.db.SkillEntity], loaded on demand via [loadSkill] — see
 * [SystemPromptEditViewModel]'s doc comment for why a plain `var` id guard is enough here.
 */
class SkillEditViewModel(
    private val skillRepository: SkillRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillEditUiState())
    val uiState: StateFlow<SkillEditUiState> = _uiState.asStateFlow()

    private var skillId: Long? = null

    /** Called by the edit screen (e.g. from a LaunchedEffect keyed on the navigated-to skill id). */
    fun loadSkill(id: Long) {
        if (skillId == id) return
        skillId = id
        viewModelScope.launch {
            val skill = skillRepository.getSkill(id) ?: return@launch
            _uiState.update { it.copy(name = skill.name, description = skill.description, content = skill.content, saved = false) }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, saved = false) }
    }

    fun onDescriptionChange(value: String) {
        _uiState.update { it.copy(description = value, saved = false) }
    }

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, saved = false) }
    }

    fun onSave() {
        val id = skillId ?: return
        val state = _uiState.value
        viewModelScope.launch {
            skillRepository.updateSkill(id, state.name, state.description, state.content)
            _uiState.update { it.copy(saved = true) }
        }
    }
}
