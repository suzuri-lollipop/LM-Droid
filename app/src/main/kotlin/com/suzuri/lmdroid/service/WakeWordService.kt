package com.suzuri.lmdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.suzuri.lmdroid.AssistActivity
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A long-running background service that listens for a custom wake word using Vosk.
 * When detected, it launches [AssistActivity] to start a full assistant session.
 */
class WakeWordService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null
    private var model: Model? = null

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settingsRepository = (application as LmDroidApplication).container.settingsRepository
        createNotificationChannel()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            WakeWordDebugManager.updateStatus(WakeWordStatus.Error("FGS Permission error: ${e.message}"))
            stopSelf()
            return
        }

        serviceScope.launch {
            combine(settingsRepository.wakeWordEnabled, settingsRepository.wakeWord) { enabled, word ->
                enabled to word
            }.collect { (enabled, word) ->
                if (enabled) {
                    startListening(word)
                } else {
                    stopListening()
                    stopSelf()
                }
            }
        }
    }

    private fun startListening(word: String) {
        listeningJob?.cancel()
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (model == null) {
                    WakeWordDebugManager.updateStatus(WakeWordStatus.LoadingModel)
                    model = loadModel()
                }
                val m = model ?: return@launch

                WakeWordDebugManager.updateStatus(WakeWordStatus.Listening)

                val grammar = "[\"$word\", \"[unk]\"]"
                val recognizer = Recognizer(m, 16000.0f, grammar)

                val bufferSize = 8000
                val minBufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                
                if (ActivityCompat.checkSelfPermission(this@WakeWordService, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    WakeWordDebugManager.updateStatus(WakeWordStatus.Error("Mic permission missing"))
                    return@launch
                }
                
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize.coerceAtLeast(bufferSize))

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    WakeWordDebugManager.updateStatus(WakeWordStatus.Error("Mic init failed"))
                    return@launch
                }

                recorder.startRecording()
                val buffer = ShortArray(bufferSize)
                while (listeningJob?.isActive == true) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        if (recognizer.acceptWaveForm(buffer, read)) {
                            val result = recognizer.result
                            WakeWordDebugManager.updateLastResult(result)
                            if (result.contains(word)) launchAssistant()
                        } else {
                            val partial = recognizer.partialResult
                            if (partial.isNotBlank()) WakeWordDebugManager.updateLastResult(partial)
                            if (partial.contains(word)) launchAssistant()
                        }
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Throwable) {
                Log.e(TAG, "Wake word listening failed", e)
                val msg = if (e is UnsatisfiedLinkError) "Native library error (16KB issue?)" else e.message ?: "Error"
                WakeWordDebugManager.updateStatus(WakeWordStatus.Error(msg))
            }
        }
    }

    private fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        WakeWordDebugManager.updateStatus(WakeWordStatus.Idle)
    }

    private fun launchAssistant() {
        serviceScope.launch(Dispatchers.Main) {
            val intent = Intent(this@WakeWordService, AssistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_WAKE_WORD_TRIGGERED, true)
            }
            startActivity(intent)
        }
    }

    private suspend fun loadModel(): Model? = withContext(Dispatchers.IO) {
        try {
            val destPath = File(filesDir, "vosk-model").absolutePath
            val modelDir = File(destPath)
            
            if (modelDir.exists() && !File(modelDir, "am").exists()) {
                Log.w(TAG, "Incomplete model directory found at $destPath, deleting...")
                modelDir.deleteRecursively()
            }

            if (!modelDir.exists()) {
                Log.d(TAG, "Starting manual asset copy for Vosk model...")
                try {
                    copyAssetDir("model", modelDir)
                    Log.d(TAG, "Model assets copied successfully to $destPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Model asset copy failed", e)
                    withContext(Dispatchers.Main) { 
                        WakeWordDebugManager.updateStatus(WakeWordStatus.Error("Copy failed: ${e.message}")) 
                    }
                    return@withContext null
                }
            }
            
            Log.d(TAG, "Initializing Vosk Model from $destPath...")
            // The actual native load happens here
            Model(destPath)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load Vosk model", e)
            val msg = when (e) {
                is UnsatisfiedLinkError -> "Native library error (16KB issue?): ${e.message}"
                else -> e.message ?: "Load error"
            }
            withContext(Dispatchers.Main) { WakeWordDebugManager.updateStatus(WakeWordStatus.Error(msg)) }
            null
        }
    }

    private fun copyAssetDir(assetDir: String, destDir: File) {
        val assetList = assets.list(assetDir) ?: run {
            Log.w(TAG, "No assets found in $assetDir")
            return
        }
        if (!destDir.exists()) {
            val created = destDir.mkdirs()
            Log.v(TAG, "Creating directory $destDir: $created")
        }
        
        for (assetName in assetList) {
            val assetPath = "$assetDir/$assetName"
            val destFile = File(destDir, assetName)
            
            // list() is slow, but we need to check if it's a directory
            val subAssets = assets.list(assetPath)
            if (subAssets.isNullOrEmpty()) {
                Log.v(TAG, "Copying file: $assetPath -> ${destFile.absolutePath}")
                copyAssetFile(assetPath, destFile)
            } else {
                copyAssetDir(assetPath, destFile)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.wake_word_notification_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, AssistActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_word_notification_title))
            .setContentText(getString(R.string.wake_word_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wake_word_service"
        const val EXTRA_WAKE_WORD_TRIGGERED = "wake_word_triggered"
    }
}
