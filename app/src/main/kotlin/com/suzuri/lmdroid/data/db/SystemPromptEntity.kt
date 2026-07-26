package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved, named system prompt — the user can register several and activate any number of them at
 * once (see SettingsRepository.selectedSystemPromptIds), the same multi-select semantics as
 * [ApiProfileEntity]'s `enabled` flag.
 */
@Entity(tableName = "system_prompts")
data class SystemPromptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val content: String,
    val createdAt: Long,
)
