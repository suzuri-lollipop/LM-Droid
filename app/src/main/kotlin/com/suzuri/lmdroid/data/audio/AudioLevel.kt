package com.suzuri.lmdroid.data.audio

import kotlin.math.abs

/** Default mic-input noise-gate threshold (normalized peak amplitude) — see SettingsRepository.micInputThreshold. */
const val DEFAULT_MIC_INPUT_THRESHOLD = 0.05f

/** Upper bound of the mic-input threshold's meaningful range — typical speech rarely peaks anywhere near full scale. */
const val MIC_INPUT_THRESHOLD_MAX = 0.5f

/** Peak amplitude of `buffer[0 until read]`, normalized to the 0f..1f range of a 16-bit PCM sample. */
fun normalizedPeakLevel(buffer: ShortArray, read: Int): Float {
    var peak = 0
    for (i in 0 until read) {
        val amplitude = abs(buffer[i].toInt())
        if (amplitude > peak) peak = amplitude
    }
    return peak / Short.MAX_VALUE.toFloat()
}
