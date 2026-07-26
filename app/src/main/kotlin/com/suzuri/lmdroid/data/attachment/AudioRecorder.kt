package com.suzuri.lmdroid.data.attachment

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Records a voice message as 16kHz mono 16-bit PCM wrapped in a WAV container — the OpenAI
 * audio-input content part accepts "wav" (or "mp3") directly, and MediaRecorder's own container
 * formats (AAC/M4A) aren't among the accepted formats, so raw [AudioRecord] plus a hand-written
 * WAV header is the simplest dependency-free way to produce a compatible file.
 */
class AudioRecorder(private val attachmentFileStore: AttachmentFileStore) {
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recordJob != null

    /** Starts recording in the background; call [stop] to finish and get the resulting file. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        if (isRecording) return
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        val file = attachmentFileStore.newAttachmentFile("wav")
        audioRecord = record
        outputFile = file

        recordJob = scope.launch(Dispatchers.IO) {
            file.outputStream().use { out ->
                // Reserve space for the 44-byte WAV header; patched in by writeWavHeader() once
                // the total data size is known, after recording stops.
                out.write(ByteArray(WAV_HEADER_SIZE))
                val buffer = ByteArray(minBufferSize)
                record.startRecording()
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
        }
    }

    /** Stops recording and finalizes the WAV header. Returns null if nothing usable was captured. */
    suspend fun stop(): File? = withContext(Dispatchers.IO) {
        val record = audioRecord ?: return@withContext null
        val file = outputFile
        recordJob?.cancelAndJoin()
        recordJob = null
        audioRecord = null
        outputFile = null
        runCatching { record.stop() }
        record.release()

        if (file == null || !file.exists() || file.length() <= WAV_HEADER_SIZE) {
            file?.delete()
            return@withContext null
        }
        writeWavHeader(file)
        file
    }

    /** Discards an in-progress recording (e.g. the user backed out) without producing a file. */
    fun cancel() {
        recordJob?.cancel()
        recordJob = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        outputFile?.delete()
        outputFile = null
    }

    private fun writeWavHeader(file: File) {
        val dataSize = file.length() - WAV_HEADER_SIZE
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intToLeBytes((36 + dataSize).toInt()))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.write(intToLeBytes(16)) // fmt chunk size
            raf.write(shortToLeBytes(1)) // audio format: PCM
            raf.write(shortToLeBytes(CHANNELS.toShort()))
            raf.write(intToLeBytes(SAMPLE_RATE))
            raf.write(intToLeBytes(byteRate))
            raf.write(shortToLeBytes((CHANNELS * BITS_PER_SAMPLE / 8).toShort()))
            raf.write(shortToLeBytes(BITS_PER_SAMPLE.toShort()))
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intToLeBytes(dataSize.toInt()))
        }
    }

    private fun intToLeBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun shortToLeBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte(),
    )

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_SIZE = 44
    }
}
