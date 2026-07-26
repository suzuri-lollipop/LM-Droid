package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.SystemPromptDao
import com.suzuri.lmdroid.data.db.SystemPromptEntity
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Manages saved system prompts (see [SystemPromptEntity]) and which ones are currently active —
 * zero or more at a time, the same multi-select semantics as
 * [ApiProfileEntity.enabled][com.suzuri.lmdroid.data.db.ApiProfileEntity]. The active selection
 * itself is stored in [SettingsRepository] (a set of ids) rather than an `isActive` column on the
 * entity, the same way the chat/system model selections already point at an [ApiProfileEntity] by id.
 */
class SystemPromptRepository(
    private val systemPromptDao: SystemPromptDao,
    private val settingsRepository: SettingsRepository,
) {
    fun observePrompts(): Flow<List<SystemPromptEntity>> = systemPromptDao.observeAll()

    suspend fun getPrompt(id: Long): SystemPromptEntity? = systemPromptDao.getById(id)

    val selectedPromptIds: Flow<Set<Long>> = settingsRepository.selectedSystemPromptIds

    /** Every active prompt's own text to prepend to a request, oldest-created first — empty when none are selected. */
    suspend fun currentActiveContents(): List<String> {
        val ids = settingsRepository.currentSelectedSystemPromptIds()
        if (ids.isEmpty()) return emptyList()
        return systemPromptDao.observeAll().first()
            .filter { it.id in ids }
            .mapNotNull { it.content.takeIf { content -> content.isNotBlank() } }
    }

    /** Creates the prompt row immediately (so the caller can navigate straight to editing it) and returns its new id. */
    suspend fun createPrompt(name: String): Long {
        return systemPromptDao.insert(
            SystemPromptEntity(
                name = name.ifBlank { "新しいシステムプロンプト" },
                content = "",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updatePrompt(id: Long, name: String, content: String) {
        val existing = systemPromptDao.getById(id) ?: return
        systemPromptDao.update(existing.copy(name = name.ifBlank { existing.name }, content = content))
    }

    /** Also removes the deleted prompt from the active selection, so it doesn't linger as a dangling id that resolves to nothing. */
    suspend fun deletePrompt(id: Long) {
        systemPromptDao.delete(id)
        val ids = settingsRepository.currentSelectedSystemPromptIds()
        if (id in ids) {
            settingsRepository.setSelectedSystemPromptIds(ids - id)
        }
    }

    /** Flips whether [id] is active, leaving every other prompt's selection untouched — several may be active simultaneously. */
    suspend fun togglePrompt(id: Long) {
        val ids = settingsRepository.currentSelectedSystemPromptIds()
        settingsRepository.setSelectedSystemPromptIds(if (id in ids) ids - id else ids + id)
    }
}
