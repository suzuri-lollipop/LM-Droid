package com.suzuri.lmdroid.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Service that creates and manages VoiceInteractionSessions.
 */
class LmDroidVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LmDroidVoiceInteractionSession(this)
    }
}
