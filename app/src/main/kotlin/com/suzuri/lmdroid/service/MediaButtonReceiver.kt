package com.suzuri.lmdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.util.Log

/**
 * Handles physical media button presses (e.g. from Bluetooth earphones).
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            Log.d(TAG, "Media button event: $event")
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        // For a simple app, we might want to trigger the assistant on long press
                        // or specific sequences. But standard Android behavior for long press
                        // is handled by the system to trigger the Assistant.
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
}
