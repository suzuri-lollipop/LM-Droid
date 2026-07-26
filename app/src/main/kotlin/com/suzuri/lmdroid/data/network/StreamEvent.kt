package com.suzuri.lmdroid.data.network

sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data class ReasoningDelta(val text: String) : StreamEvent()

    /**
     * The model chose to call one or more tools instead of (or before) answering directly —
     * emitted once, after the stream finishes reassembling every fragment of every call (OpenAI
     * streams a call's `arguments` a few characters at a time, matched up by position rather than
     * sent whole). The caller is expected to execute each call, append the results to the
     * conversation, and re-invoke [OpenAiApiClient.streamChatCompletion] so the model can continue
     * reasoning with them.
     */
    data class ToolCallsRequested(val toolCalls: List<RequestedToolCall>) : StreamEvent()
    object Done : StreamEvent()
}

/** A fully-reassembled tool call the model asked to make. [argumentsJson] is the raw (unparsed) JSON object the model produced for the function's parameters. */
data class RequestedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)
