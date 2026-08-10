package com.suzuri.lmdroid.service

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * A minimal RecognitionService required for the system to recognize this app as a valid assistant.
 */
class LmDroidRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening")
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(TAG, "onStopListening")
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "onCancel")
    }

    companion object {
        private const val TAG = "LmDroidRecognitionSvc"
    }
}
