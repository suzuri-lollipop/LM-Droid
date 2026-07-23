package com.suzuri.lmdroid.data.network

sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    object Done : StreamEvent()
}
