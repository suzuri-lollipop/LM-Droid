package com.suzuri.lmdroid.data.settings

import com.suzuri.lmdroid.data.db.ThinkingEffort

data class AppSettings(
    val apiKey: String?,
    val model: String,
    val baseUrl: String,
    val markdownEnabled: Boolean = true,
    // Global 思考(thinking) effort for reasoning-capable local models (e.g. Qwen3.8, Gemma served
    // via llama-server) — OFF is forwarded as chat_template_kwargs.enable_thinking=false;
    // LOW/MEDIUM/XHIGH (default) are forwarded as the request's top-level reasoning_effort field.
    // A model/template that doesn't support either simply ignores them. See
    // OpenAiApiClient.streamChatCompletion.
    val thinkingEffort: ThinkingEffort = ThinkingEffort.MEDIUM,
    // Global 記憶(memory retention) toggle for models that support it (e.g. Qwen3.8) — true
    // (default) leaves the model/server's own default behavior alone; false is forwarded as
    // chat_template_kwargs.enable_memory=false. A template that doesn't support this simply
    // ignores it.
    val memoryEnabled: Boolean = true,
    // The registered ApiProfileEntity's own display name (e.g. "ローカルサーバー") — null when no
    // profile backs this AppSettings at all (nothing configured yet). Lets a UI show which profile
    // is actually active (see AssistScreen's title) without having to look the profile back up.
    val profileName: String? = null,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
