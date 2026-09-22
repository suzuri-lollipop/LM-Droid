package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.suzuri.lmdroid.data.network.ModelCapabilities

/**
 * One model a [ApiProfileEntity] offers, auto-populated from that provider's model list (see
 * ApiProfileRepository.refreshModels).
 *
 * Besides its id, a model row carries what the server reported (best-effort) about the model's
 * own thinking controls — see [ModelCapabilities] and the four nullable flags below. null means
 * "unknown" (the server advertised nothing) and the chat screen offers the control as always;
 * only a definite false hides it (see ModelSelectorButton).
 */
@Entity(
    tableName = "api_models",
    foreignKeys = [
        ForeignKey(
            entity = ApiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ApiModelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val modelId: String,
    // 思考 switch's OFF side (chat_template_kwargs.enable_thinking) actually reaches the model's
    // chat template.
    val supportsThinking: Boolean? = null,
    // The LOW/MEDIUM/XHIGH effort levels (top-level reasoning_effort) are honored.
    val supportsReasoningEffort: Boolean? = null,
    // 思考予算 (top-level reasoning_budget_tokens) is understood by the server.
    val supportsThinkingBudget: Boolean? = null,
    // 記憶 (chat_template_kwargs.enable_memory) actually reaches the model's chat template.
    val supportsMemory: Boolean? = null,
)
