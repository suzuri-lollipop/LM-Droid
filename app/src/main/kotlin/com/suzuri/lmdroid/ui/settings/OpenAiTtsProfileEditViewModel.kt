package com.suzuri.lmdroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenAiTtsProfileEditUiState(
    val profileName: String = "",
    val apiKey: String = "",
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    val model: String = ApiProfileEntity.DEFAULT_OPENAI_TTS_MODEL,
    val voice: String = ApiProfileEntity.DEFAULT_OPENAI_TTS_VOICE,
    val availableModels: List<String> = listOf("tts-1", "tts-1-hd"),
    val availableVoices: List<String> = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer"),
    val testState: TestConnectionState = TestConnectionState.Idle,
    val saved: Boolean = false,
)

class OpenAiTtsProfileEditViewModel(
    private val apiProfileRepository: ApiProfileRepository,
    private val openAiTtsClient: com.suzuri.lmdroid.data.tts.OpenAiTtsClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpenAiTtsProfileEditUiState())
    val uiState: StateFlow<OpenAiTtsProfileEditUiState> = _uiState.asStateFlow()

    private var profileId: Long? = null

    fun loadProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        viewModelScope.launch {
            val profile = apiProfileRepository.getProfile(id) ?: return@launch
            val apiKey = apiProfileRepository.decryptApiKey(profile).orEmpty()
            _uiState.update {
                it.copy(
                    profileName = profile.name,
                    apiKey = apiKey,
                    baseUrl = profile.baseUrl,
                    model = profile.openaiTtsModel ?: ApiProfileEntity.DEFAULT_OPENAI_TTS_MODEL,
                    voice = profile.openaiTtsVoice ?: ApiProfileEntity.DEFAULT_OPENAI_TTS_VOICE,
                    testState = TestConnectionState.Idle,
                    saved = false,
                )
            }
        }
    }

    fun onProfileNameChange(value: String) {
        _uiState.update { it.copy(profileName = value, saved = false) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, saved = false, testState = TestConnectionState.Idle) }
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, saved = false) }
    }

    fun onVoiceChange(value: String) {
        _uiState.update { it.copy(voice = value, saved = false) }
    }

    fun onSave() {
        val id = profileId ?: return
        val state = _uiState.value
        viewModelScope.launch {
            apiProfileRepository.updateOpenAiTtsProfile(
                id = id,
                name = state.profileName,
                apiKey = state.apiKey,
                baseUrl = state.baseUrl,
                model = state.model,
                voice = state.voice,
            )
            _uiState.update { it.copy(saved = true) }
        }
    }

    fun onFetchOptions() {
        val apiKey = _uiState.value.apiKey
        val baseUrl = _uiState.value.baseUrl
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(testState = TestConnectionState.Failure("APIキーを入力してください。")) }
            return
        }
        _uiState.update { it.copy(testState = TestConnectionState.Testing) }
        viewModelScope.launch {
            val modelsResult = openAiTtsClient.listModels(apiKey, baseUrl)
            val voicesResult = openAiTtsClient.listVoices(apiKey, baseUrl)
            
            _uiState.update { state ->
                val models = modelsResult.getOrNull()?.takeIf { it.isNotEmpty() } ?: state.availableModels
                val voices = voicesResult.getOrNull()?.takeIf { it.isNotEmpty() } ?: state.availableVoices
                
                state.copy(
                    availableModels = models,
                    availableVoices = voices,
                    testState = if (modelsResult.isSuccess) TestConnectionState.Success else TestConnectionState.Failure("オプションの取得に失敗しました。")
                )
            }
        }
    }
}
