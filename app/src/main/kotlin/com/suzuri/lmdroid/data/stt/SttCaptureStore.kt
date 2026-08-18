package com.suzuri.lmdroid.data.stt

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * File storage for the developer "dump captured audio" diagnostic (see WavFileWriter): the raw mic
 * input captured during a local voice-input session, written to app-private *cache* storage so it
 * can be pulled off the device and inspected to tell a codec/audio-quality problem apart from a
 * model problem. Cache storage (not filesDir) is deliberate — the OS may evict it under pressure
 * and [cleanupOld] removes stale files, so a leak of large WAVs stays bounded.
 */
object SttCaptureStore {
    private const val DIR_NAME = "stt-capture"
    // Keep at most this many days of captures; older files are dropped by [cleanupOld].
    private const val MAX_AGE_DAYS = 7L

    fun captureDir(context: Context): File =
        File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** A fresh, collision-free (timestamp-based) capture file inside the capture dir. */
    fun newCaptureFile(context: Context): File =
        File(captureDir(context), "capture-${System.currentTimeMillis()}.wav")

    /** All captured WAVs, newest first. */
    fun listFiles(context: Context): List<File> =
        captureDir(context).listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Deletes every captured file; returns how many were removed. */
    fun deleteAll(context: Context): Int {
        val files = listFiles(context)
        files.forEach { runCatching { it.delete() } }
        return files.size
    }

    /** Removes captures older than [MAX_AGE_DAYS] — called on app start to keep the cache bounded. */
    fun cleanupOld(context: Context) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
        listFiles(context).filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
    }
}
