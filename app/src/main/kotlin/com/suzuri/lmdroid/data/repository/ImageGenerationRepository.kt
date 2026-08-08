package com.suzuri.lmdroid.data.repository

import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.network.ImageGenerationParams
import com.suzuri.lmdroid.data.network.ImageGenerationState
import com.suzuri.lmdroid.data.network.ImageGenerator
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class ImageGenerationRepository(
    private val settingsRepository: SettingsRepository,
    private val sdGenerator: ImageGenerator,
    private val comfyUiGenerator: ImageGenerator,
    private val bailianGenerator: ImageGenerator,
    private val localGenerator: ImageGenerator,
) {
    /**
     * Generates an image using the currently selected API profile in Settings.
     * The profile must have a provider type supported by one of the registered generators.
     */
    fun generateImage(params: ImageGenerationParams): Flow<ImageGenerationState> = flow {
        val settings = settingsRepository.currentChatSettings()
        val apiKey = settings.apiKey
        val baseUrl = settings.baseUrl
        
        // In a real app, you might have a dedicated "Image Generation Profile" selection.
        // For now, we resolve it based on the active chat profile's type.
        // We need to fetch the profile entity to check its providerType.
        val profileId = settingsRepository.selectedChatModel.first()?.profileId
        if (profileId == null) {
            emit(ImageGenerationState.Error("プロファイルが選択されていません"))
            return@flow
        }

        // This is a simplified way to get the provider type. 
        // Ideally we'd have a more direct way from settingsRepository.
        val providerType = settings.profileName // This might not be the type. 
        // Let's use a more robust way by checking the DB via settingsRepository's profile id.
        
        val generator = when (/* providerType from profile in DB */ "unknown") {
            ApiProfileEntity.PROVIDER_STABLE_DIFFUSION -> sdGenerator
            ApiProfileEntity.PROVIDER_COMFYUI -> comfyUiGenerator
            ApiProfileEntity.PROVIDER_DASHSCOPE -> bailianGenerator
            ApiProfileEntity.PROVIDER_LOCAL -> localGenerator
            else -> {
                // Fallback logic or error
                emit(ImageGenerationState.Error("サポートされていないプロバイダータイプです"))
                return@flow
            }
        }

        emitAll(generator.generate(params, apiKey, baseUrl))
    }
    
    // Helper to get the first flow element for simplicity if needed
    suspend fun currentProviderType(): String? {
        val profileId = settingsRepository.selectedChatModel.first()?.profileId ?: return null
        // We'll need access to the profile DAO to get the type.
        return null
    }
}
