package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.SkillEntity
import com.suzuri.lmdroid.data.repository.SkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SkillListViewModel(
    private val skillRepository: SkillRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillListUiState())
    val uiState: StateFlow<SkillListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                skillRepository.observeSkills(),
                skillRepository.selectedSkillIds,
            ) { skills, selectedIds -> skills.map { it.toUiModel(selectedIds) } }
                .collect { skills -> _uiState.update { it.copy(skills = skills) } }
        }
    }

    /** Creates the skill row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createSkill(name: String): Long = skillRepository.createSkill(name)

    /** Several skills may be active simultaneously — toggling one leaves every other skill's selection untouched. */
    fun onToggleSkill(id: Long) {
        viewModelScope.launch { skillRepository.toggleSkill(id) }
    }

    fun onDeleteSkill(id: Long) {
        viewModelScope.launch { skillRepository.deleteSkill(id) }
    }

    private fun SkillEntity.toUiModel(selectedIds: Set<Long>) =
        SkillUiModel(id = id, name = name, description = description, isSelected = id in selectedIds)
}
