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
 *
 * [prepare] (network synthesis for a VOICEVOX-compatible profile) and [play] (actual audio output)
 * are deliberately separate suspend functions rather than one combined call: AssistViewModel runs
 * a chain of [prepare] calls concurrently with [play] of the *previous* chunk, so the next
 * sentence's synthesis isn't sitting idle while the current one is still being spoken — a
 * combined "synthesize then immediately play" call would serialize the two and reintroduce a gap
 * between every sentence.
 */
class AssistSpeechPlayer(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val voicevoxCompatibleClient: VoicevoxCompatibleClient,
    private val onDeviceSpeechSynthesizer: OnDeviceSpeechSynthesizer,
) {
    // MediaPlayer is explicitly documented as not thread-safe, but this one gets touched from more
    // than one thread in practice: its own onCompletion/onError callbacks fire on whatever
    // Looper thread it was created on, while stop() can run from wherever a cancellation
    // (AssistViewModel cancelling playbackJob) happens to land. Without synchronizing every read
    // and every release() call, both paths could race to release() the same instance — a double
    // release/use-after-release on native MediaPlayer state, which is exactly the kind of bug that
    // surfaces as a native "destroyed mutex" crash rather than a catchable Kotlin exception.
    private val mediaPlayerLock = Any()
    private var mediaPlayer: MediaPlayer? = null

    /** What [play] needs to actually speak a chunk — the result of whatever synthesis [prepare] did (or didn't need to do). */
    sealed class PreparedSpeech {
        data class OnDevice(val text: String) : PreparedSpeech()
        data class Wav(val audioBytes: ByteArray) : PreparedSpeech()
    }

    /**
     * Does whatever slow work (a VOICEVOX-compatible profile's network round-trip) is needed
     * before [text] can be played, without playing it yet. Returns null when there's nothing to
     * speak at all (e.g. a chunk that was pure Markdown noise once stripped), so callers can skip
     * queuing it for [play] entirely.
     */
    suspend fun prepare(text: String): PreparedSpeech? {
        // The assistant overlay's replies are Markdown (see AssistScreen's own rendering) — spoken
        // aloud as-is, both TTS backends would narrate every **/#/`` as a literal character.
        val spokenText = markdownToSpeechText(text)
        if (spokenText.isBlank()) return null

        val profile = settingsRepository.currentTtsProfile() ?: return PreparedSpeech.OnDevice(spokenText)
        val speakerId = profile.voicevoxSpeakerId ?: ApiProfileEntity.DEFAULT_VOICEVOX_SPEAKER_ID
        val result = voicevoxCompatibleClient.synthesize(profile.baseUrl, spokenText, speakerId)
        val audioBytes = result.getOrNull()
        if (audioBytes == null) {
            Log.w(TAG, "prepare: VOICEVOX-compatible synthesis failed, falling back to on-device voice", result.exceptionOrNull())
            return PreparedSpeech.OnDevice(spokenText)
        }
        return PreparedSpeech.Wav(audioBytes)
    }

    /** Actually speaks a chunk [prepare] already did the slow work for — the part that must stay strictly sequential (see AssistViewModel's playback queue), since audio output can't overlap itself. */
    suspend fun play(prepared: PreparedSpeech) {
        when (prepared) {
            is PreparedSpeech.OnDevice -> onDeviceSpeechSynthesizer.speak(prepared.text)
            is PreparedSpeech.Wav -> playWav(prepared.audioBytes)
        }
    }

    private suspend fun playWav(audioBytes: ByteArray) {
        val file = File.createTempFile("assist_tts_", ".wav", context.cacheDir)
        try {
            file.writeBytes(audioBytes)
            suspendCancellableCoroutine { continuation ->
                // Whichever of finish()/stop() gets here first "claims" the player and releases
                // it exactly once; the other sees mediaPlayer already cleared (or pointing at a
                // different instance) and backs off instead of touching an already-released one.
                fun finish(player: MediaPlayer) {
                    val claimed = synchronized(mediaPlayerLock) {
                        if (mediaPlayer !== player) return@synchronized false
                        mediaPlayer = null
                        true
                    }
                    if (claimed) runCatching { player.release() }
                    if (continuation.isActive) continuation.resume(Unit)
                }

                val player = MediaPlayer()
                synchronized(mediaPlayerLock) { mediaPlayer = player }
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
        // Atomically takes ownership of whatever mediaPlayer currently is (if anything) so
        // finish()'s own claim check above sees it already cleared and won't also try to release
        // it — see mediaPlayerLock's doc comment.
        val player = synchronized(mediaPlayerLock) {
            mediaPlayer.also { mediaPlayer = null }
        }
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
    }

    private companion object {
        const val TAG = "AssistSpeechPlayer"
    }
}
