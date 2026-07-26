package com.suzuri.lmdroid.data.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One step of an assistant reply's "thinking process", in the order it actually happened —
 * chain-of-thought reasoning and tool activity (web search / page fetches) interleaved exactly as
 * the model produced them, rather than grouped into separate fixed blocks (Claude-style). A
 * consecutive run of reasoning deltas is coalesced into a single [Reasoning] entry; a tool call
 * always starts a new [ToolActivity] entry. Persisted as a JSON array in
 * [MessageEntity.thinkingTimelineJson].
 */
@Serializable
sealed class ThinkingTimelineEntry {
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : ThinkingTimelineEntry()

    /** A tool the model called mid-reply — [label] identifies what was done (e.g. a search query or fetched URL), [content] is the result it got back. */
    @Serializable
    @SerialName("tool_activity")
    data class ToolActivity(val label: String, val content: String) : ThinkingTimelineEntry()
}
