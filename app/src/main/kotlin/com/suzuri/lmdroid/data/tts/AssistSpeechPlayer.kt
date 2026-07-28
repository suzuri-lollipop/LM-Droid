package com.suzuri.lmdroid.data.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Speaks the assistant overlay's replies aloud (see AssistViewModel) — either through the
 * registered VOICEVOX-compatible profile selected in Settings → 音声 (see
 * SettingsRepository.selectedTtsProfileId), or, when none is selected, this device's own built-in
 * text-to-speech. Never throws: a failed/unreachable profile falls back to on-device speech rather
 * than leaving the reply silent, the same way a failed web search still lets a reply finish.
 */
class AssistSpeechPlayer(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val voicevoxCompatibleClient: VoicevoxCompatibleClient,
    private val onDeviceSpeechSynthesizer: OnDeviceSpeechSynthesizer,
) {
    private var mediaPlayer: MediaPlayer? = null

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val profile = settingsRepository.currentTtsProfile()
        if (profile == null) {
            onDeviceSpeechSynthesizer.speak(text)
            return
        }

        val speakerId = profile.voicevoxSpeakerId ?: ApiProfileEntity.DEFAULT_VOICEVOX_SPEAKER_ID
        val result = voicevoxCompatibleClient.synthesize(profile.baseUrl, text, speakerId)
        val audioBytes = result.getOrNull()
        if (audioBytes == null) {
            Log.w(TAG, "speak: VOICEVOX-compatible synthesis failed, falling back to on-device voice", result.exceptionOrNull())
            onDeviceSpeechSynthesizer.speak(text)
            return
        }
        playWav(audioBytes)
    }

    private suspend fun playWav(audioBytes: ByteArray) {
        val file = File.createTempFile("assist_tts_", ".wav", context.cacheDir)
        try {
            file.writeBytes(audioBytes)
            suspendCancellableCoroutine { continuation ->
                fun finish(player: MediaPlayer) {
                    runCatching { player.release() }
                    if (mediaPlayer === player) mediaPlayer = null
                    if (continuation.isActive) continuation.resume(Unit)
                }

                val player = MediaPlayer()
                mediaPlayer = player
                try {
                    player.setDataSource(file.absolutePath)
                    player.setOnPreparedListener { it.start() }
                    player.setOnCompletionListener { finish(it) }
                    player.setOnErrorListener { mp, what, extra ->
                        Log.w(TAG, "playWav: MediaPlayer error what=$what extra=$extra")
                        finish(mp)
                        true
                    }
                    player.prepareAsync()
                } catch (e: IOException) {
                    Log.w(TAG, "playWav: failed to start playback", e)
                    finish(player)
                }
                continuation.invokeOnCancellation { stop() }
            }
        } finally {
            file.delete()
        }
    }

    /** Stops any in-progress speech from either backend — called when the user dismisses the overlay or starts a follow-up question, so playback never keeps going after the user has moved on. */
    fun stop() {
        onDeviceSpeechSynthesizer.stop()
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
    }

    private companion object {
        const val TAG = "AssistSpeechPlayer"
    }
}
