package com.suzuri.lmdroid.data.db

/**
 * How hard a reasoning-capable model (e.g. Qwen3.8) should think before answering — OFF forces
 * chat_template_kwargs.enable_thinking=false (see OpenAiApiClient.streamChatCompletion), while
 * LOW/MEDIUM/XHIGH are forwarded as the request's top-level `reasoning_effort` field instead. A
 * model/template that recognizes neither field simply ignores them.
 */
enum class ThinkingEffort {
    OFF,
    LOW,
    MEDIUM,
    XHIGH,
}
