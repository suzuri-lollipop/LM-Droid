package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.SkillDao
import com.suzuri.lmdroid.data.db.SkillEntity
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Manages saved skills (see [SkillEntity]) and which ones are currently active — zero or more at
 * once, the same multi-select semantics as [SystemPromptRepository]. Unlike a system prompt, a
 * skill's [SkillEntity.content] is never injected wholesale into every request: only active
 * skills' name/description are advertised to the model as a compact catalog (see
 * ConversationRepository), and the full [SkillEntity.content] is fetched on demand — either by
 * the model itself calling the "use_skill" tool ([findActiveSkillByName]), or by the user
 * explicitly forcing one for their next message.
 */
class SkillRepository(
    private val skillDao: SkillDao,
    private val settingsRepository: SettingsRepository,
) {
    fun observeSkills(): Flow<List<SkillEntity>> = skillDao.observeAll()

    suspend fun getSkill(id: Long): SkillEntity? = skillDao.getById(id)

    val selectedSkillIds: Flow<Set<Long>> = settingsRepository.selectedSkillIds

    /** Every active skill, oldest-created first — empty when none are selected. */
    suspend fun currentActiveSkills(): List<SkillEntity> {
        val ids = settingsRepository.currentSelectedSkillIds()
        if (ids.isEmpty()) return emptyList()
        return skillDao.observeAll().first().filter { it.id in ids }
    }

    /** Case-insensitive lookup among the currently active skills — used to resolve a "use_skill" tool call. */
    suspend fun findActiveSkillByName(name: String): SkillEntity? =
        currentActiveSkills().find { it.name.equals(name, ignoreCase = true) }

    /** Creates the skill row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createSkill(name: String): Long {
        return skillDao.insert(
            SkillEntity(
                name = name.ifBlank { "新しいスキル" },
                description = "",
                content = "",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateSkill(id: Long, name: String, description: String, content: String) {
        val existing = skillDao.getById(id) ?: return
        skillDao.update(existing.copy(name = name.ifBlank { existing.name }, description = description, content = content))
    }

    /** Also removes the deleted skill from the active selection, so it doesn't linger as a dangling id that resolves to nothing. */
    suspend fun deleteSkill(id: Long) {
        skillDao.delete(id)
        val ids = settingsRepository.currentSelectedSkillIds()
        if (id in ids) {
            settingsRepository.setSelectedSkillIds(ids - id)
        }
    }

    /** Flips whether [id] is active, leaving every other skill's selection untouched — several may be active simultaneously. */
    suspend fun toggleSkill(id: Long) {
        val ids = settingsRepository.currentSelectedSkillIds()
        settingsRepository.setSelectedSkillIds(if (id in ids) ids - id else ids + id)
    }
}
