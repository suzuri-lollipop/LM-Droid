package com.suzuri.lmdroid.data.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming WAV writer for 16-bit PCM mono — used to dump raw captured mic audio for diagnostic
 * inspection (see SttCaptureStore). Mirrors the WAV layout [AudioRecorder] produces, but accepts
 * [ShortArray] samples incrementally (the capture loop reads into a ShortArray) and patches the
 * RIFF/data sizes in [finalizeWav] once recording stops.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
) {
    private val bitsPerSample = 16
    private val raf = RandomAccessFile(file, "rw")
    private var dataSize = 0L

    init {
        // Reserve the header space; the real sizes are patched in by finalizeWav().
        raf.write(ByteArray(HEADER_SIZE))
    }

    /** Appends [length] samples from [data] as little-endian 16-bit PCM. */
    @Synchronized
    fun write(data: ShortArray, length: Int) {
        val bytes = ByteArray(length * 2)
        var o = 0
        for (i in 0 until length) {
            val v = data[i].toInt()
            bytes[o++] = (v and 0xff).toByte()
            bytes[o++] = ((v shr 8) and 0xff).toByte()
        }
        raf.write(bytes)
        dataSize += bytes.size
    }

    /** Patches the RIFF/data sizes and closes. Safe to call more than once. */
    @Synchronized
    fun finalizeWav() {
        if (raf.channel.isOpen) {
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intToLe((36 + dataSize).toInt()))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.write(intToLe(16)) // fmt chunk size
            raf.write(shortToLe(1.toShort())) // audio format: PCM
            raf.write(shortToLe(channels.toShort()))
            raf.write(intToLe(sampleRate))
            raf.write(intToLe(sampleRate * channels * bitsPerSample / 8))
            raf.write(shortToLe((channels * bitsPerSample / 8).toShort()))
            raf.write(shortToLe(bitsPerSample.toShort()))
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intToLe(dataSize.toInt()))
        }
        close()
    }

    @Synchronized
    fun close() {
        runCatching { raf.close() }
    }

    private fun intToLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun shortToLe(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte(),
    )

    private companion object {
        const val HEADER_SIZE = 44
    }
}
