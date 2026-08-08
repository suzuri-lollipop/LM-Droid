package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.ApiProfileDao
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.network.ImageGenerationParams
import com.suzuri.lmdroid.data.network.ImageGenerationState
import com.suzuri.lmdroid.data.network.ImageGenerator
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

class ImageGenerationRepository(
    private val settingsRepository: SettingsRepository,
    private val apiProfileRepository: ApiProfileRepository,
    private val apiProfileDao: ApiProfileDao,
    private val sdGenerator: ImageGenerator,
    private val comfyUiGenerator: ImageGenerator,
    private val bailianGenerator: ImageGenerator,
    private val localGenerator: ImageGenerator,
) {
    /**
     * Generates an image using the currently selected API profile in Settings.
     * If the chat profile doesn't support image generation, it looks for the first enabled
     * profile that does.
     */
    fun generateImage(params: ImageGenerationParams): Flow<ImageGenerationState> = flow {
        val selected = settingsRepository.selectedChatModel.first()
        val profileId = selected?.profileId
        
        var profile = profileId?.let { apiProfileDao.getById(it) }
        
        val imageProviderTypes = listOf(
            ApiProfileEntity.PROVIDER_STABLE_DIFFUSION,
            ApiProfileEntity.PROVIDER_COMFYUI,
            ApiProfileEntity.PROVIDER_DASHSCOPE,
            ApiProfileEntity.PROVIDER_LOCAL
        )
        
        if (profile == null || profile.providerType !in imageProviderTypes) {
            profile = apiProfileDao.getAll().firstOrNull { it.enabled && it.providerType in imageProviderTypes }
        }

        if (profile == null) {
            emit(ImageGenerationState.Error("有効な画像生成用プロファイルが見つかりません。API設定から追加してください。"))
            return@flow
        }

        val apiKey = apiProfileRepository.decryptApiKey(profile)
        val baseUrl = profile.baseUrl
        
        val generator = when (profile.providerType) {
            ApiProfileEntity.PROVIDER_STABLE_DIFFUSION -> sdGenerator
            ApiProfileEntity.PROVIDER_COMFYUI -> comfyUiGenerator
            ApiProfileEntity.PROVIDER_DASHSCOPE -> bailianGenerator
            ApiProfileEntity.PROVIDER_LOCAL -> localGenerator
            else -> {
                emit(ImageGenerationState.Error("サポートされていないプロバイダータイプです: ${profile.providerType}"))
                return@flow
            }
        }

        emitAll(generator.generate(params, apiKey, baseUrl))
    }
}
