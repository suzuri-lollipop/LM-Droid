package com.suzuri.lmdroid.service

import android.content.Intent
import android.media.session.MediaSession
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import android.view.KeyEvent

/**
 * The main entry point for the system's Voice Interaction framework.
 * This service runs in the background and manages the hotword detector and session life cycle.
 */
class LmDroidVoiceInteractionService : VoiceInteractionService() {

    private var mediaSession: MediaSession? = null

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "onReady: Assistant service is now ready")
        instance = this
        
        // Register a MediaSession to handle headset button events.
        // Even if we don't play media, this helps the system route media buttons to us
        // when we are the active assistant.
        mediaSession = MediaSession(this, "LmDroidAssistant").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    Log.d(TAG, "onMediaButtonEvent: keyCode=${event?.keyCode}")
                    if (event?.action == KeyEvent.ACTION_DOWN) {
                        // Any button press can potentially trigger the assistant if appropriate.
                        // Standard behavior for long press is handled by the system.
                        // But some headsets send specific keys.
                        showSession(Bundle(), 0)
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.d(TAG, "onLaunchVoiceAssistFromKeyguard: Triggering session")
        showSession(Bundle(), 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")
        if (action == ACTION_TRIGGER || 
            action == Intent.ACTION_VOICE_COMMAND ||
            action == Intent.ACTION_ASSIST ||
            action == "android.intent.action.VOICE_ASSIST") {
            Log.d(TAG, "Triggering assistant session from action: $action")
            try {
                showSession(Bundle(), 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show session", e)
                // Fallback: try to start AssistActivity
                val assistIntent = Intent(this, com.suzuri.lmdroid.AssistActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(assistIntent)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.release()
        mediaSession = null
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
