package com.suzuri.lmdroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A flattened (profile, model) row for every model offered by an *enabled* profile — the data source for the chat/system model pickers. */
data class ModelOptionRow(
    val profileId: Long,
    val profileName: String,
    val modelId: String,
    // See ApiProfileEntity.defaultThinkingEffort/defaultMemoryEnabled — carried along so
    // selecting this option (see ChatViewModel.onSelectModel) can seed the chat screen's 思考
    // effort selector and 記憶 toggle without a separate lookup.
    val defaultThinkingEffort: ThinkingEffort? = null,
    val defaultMemoryEnabled: Boolean? = null,
    // See ApiModelEntity's same-named fields — carried along so the model menu can hide the
    // controls this model demonstrably doesn't support, and so ChatViewModel.onSelectModel can
    // clamp a now-unsupported selection, without a separate lookup.
    val supportsThinking: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportsThinkingBudget: Boolean? = null,
    val supportsMemory: Boolean? = null,
)

@Dao
interface ApiModelDao {
    @Insert
    suspend fun insertAll(models: List<ApiModelEntity>)

    @Query("SELECT * FROM api_models WHERE profileId = :profileId ORDER BY modelId ASC")
    fun observeByProfile(profileId: Long): Flow<List<ApiModelEntity>>

    @Query("DELETE FROM api_models WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)

    @Query(
        "SELECT api_models.profileId AS profileId, api_profiles.name AS profileName, api_models.modelId AS modelId, " +
            "api_profiles.defaultThinkingEffort AS defaultThinkingEffort, " +
            "api_profiles.defaultMemoryEnabled AS defaultMemoryEnabled, " +
            "api_models.supportsThinking AS supportsThinking, " +
            "api_models.supportsReasoningEffort AS supportsReasoningEffort, " +
            "api_models.supportsThinkingBudget AS supportsThinkingBudget, " +
            "api_models.supportsMemory AS supportsMemory " +
            "FROM api_models INNER JOIN api_profiles ON api_models.profileId = api_profiles.id " +
            "WHERE api_profiles.enabled = 1 AND api_profiles.providerType = 'openai_compatible' " +
            "ORDER BY api_profiles.createdAt ASC, api_models.modelId ASC",
    )
    fun observeEnabledModelOptions(): Flow<List<ModelOptionRow>>
}
