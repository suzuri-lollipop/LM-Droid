package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved, named skill — a bundle of instructions the model can pull into context on demand,
 * mirroring [SystemPromptEntity]'s "register several, activate any number at once" semantics
 * (see SkillRepository.selectedSkillIds). Unlike a system prompt, [content] is never injected
 * wholesale: only [name] and [description] are always visible to the model (a compact catalog),
 * and [content] is loaded only once the skill is actually invoked — either by the model itself
 * (the "use_skill" tool, see ConversationRepository) or explicitly by the user.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String,
    val content: String,
    val createdAt: Long,
)
