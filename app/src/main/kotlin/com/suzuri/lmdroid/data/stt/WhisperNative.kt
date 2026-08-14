package com.suzuri.lmdroid.data.stt

class WhisperNative {
    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    external fun init(modelPath: String): Long
    external fun full(context: Long, samples: FloatArray, language: String): Int
    external fun getNSegments(context: Long): Int
    external fun getSegmentText(context: Long, index: Int): String
    external fun getSegmentNoSpeechProb(context: Long, index: Int): Float
    external fun free(context: Long)
}
