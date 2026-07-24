package com.suzuri.lmdroid.data.network

sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data class ReasoningDelta(val text: String) : StreamEvent()
    object Done : StreamEvent()
}
