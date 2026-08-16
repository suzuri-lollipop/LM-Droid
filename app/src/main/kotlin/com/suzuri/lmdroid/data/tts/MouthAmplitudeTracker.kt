package com.suzuri.lmdroid.data.tts

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Drives a live 0..1 mouth-open amplitude from whatever audio is actually playing in an audio
 * session, via Android's Visualizer effect — this is what lets the character's mouth track the
 * real shape of the TTS voice (loud syllables open it, pauses close it) instead of a fixed
 * animation loop that looks the same every time. Visualizer taps the decoded PCM after whatever
 * codec produced it, not the source bytes, so this works the same way regardless of which TTS
 * backend is speaking (OpenAI's MP3, VOICEVOX-compatible's WAV, or the on-device engine's own
 * internal output) without this class needing to know or care which one it is.
 *
 * Never throws: a device/engine that doesn't support Visualizer on a given session just leaves
 * amplitude at 0, so the caller falls back to whatever default lip motion it already has instead
 * of crashing playback over a cosmetic feature.
 */
class MouthAmplitudeTracker {
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var visualizer: Visualizer? = null

    /**
     * Starts tracking [audioSessionId]'s output. Call [stop] once that session's audio ends.
     *
     * [referenceRms] is the loudness (RMS of the 0..127 unsigned-PCM deviation from centre) that
     * maps to a fully-open mouth — this is the "threshold" exposed as Settings →
     * キャラクター's lip-sync sensitivity slider (see CharacterSettings.lipSyncThreshold). Lower
     * values make the mouth react to quieter audio (more sensitive); higher values need louder
     * audio before it opens. Defaults to [DEFAULT_REFERENCE_RMS], deliberately low (~15% of the
     * 0..127 range) so ordinary speech reaches close to 1.0 rather than only clipped peaks doing so.
     */
    fun start(audioSessionId: Int, referenceRms: Float = DEFAULT_REFERENCE_RMS) {
        stop()
        visualizer = runCatching {
            Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform == null || waveform.isEmpty()) return
                            var sumSquares = 0.0
                            for (b in waveform) {
                                val centered = (b.toInt() and 0xFF) - 128
                                sumSquares += centered.toDouble() * centered.toDouble()
                            }
                            val rms = sqrt(sumSquares / waveform.size)
                            _amplitude.value = (rms / referenceRms).toFloat().coerceIn(0f, 1f)
                        }

                        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false,
                )
                enabled = true
            }
        }.onFailure { e ->
            Log.w(TAG, "start: Visualizer unavailable for session=$audioSessionId, lip sync will use default motion", e)
        }.getOrNull()
    }

    /** Stops tracking and resets amplitude to 0 (mouth closed) — call when the clip finishes, errors, or is cancelled. */
    fun stop() {
        visualizer?.let { v ->
            runCatching { v.enabled = false }
            runCatching { v.release() }
        }
        visualizer = null
        _amplitude.value = 0f
    }

    companion object {
        const val TAG = "MouthAmplitudeTracker"
        const val DEFAULT_REFERENCE_RMS = 32f
    }
}
