package com.suzuri.lmdroid.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * The main entry point for the system's Voice Interaction framework.
 * This service runs in the background and manages the hotword detector and session life cycle.
 */
class LmDroidVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "onReady")
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent, action=${intent?.action}")
        if (intent?.action == ACTION_TRIGGER) {
            Log.d(TAG, "Triggering assistant session from intent")
            try {
                showSession(Bundle(), 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show session", e)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "LmDroidVoiceInteraction"
        const val ACTION_TRIGGER = "com.suzuri.lmdroid.service.TRIGGER_ASSISTANT"
        
        private var instance: LmDroidVoiceInteractionService? = null
        
        fun trigger(context: android.content.Context) {
            val service = instance
            Log.d(TAG, "trigger: service instance exists=${service != null}")
            if (service != null) {
                Log.d(TAG, "Triggering via instance")
                try {
                    service.showSession(Bundle(), 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show session via instance", e)
                }
            } else {
                Log.d(TAG, "Triggering via startService")
                val intent = Intent(context, LmDroidVoiceInteractionService::class.java).apply {
                    action = ACTION_TRIGGER
                }
                context.startService(intent)
            }
        }
    }
}
