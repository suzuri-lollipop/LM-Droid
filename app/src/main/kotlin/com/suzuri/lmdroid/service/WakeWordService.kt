package com.suzuri.lmdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.AssistActivity
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.vosk.VoskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    private lateinit var voskRepository: VoskRepository

    private var isPausedByAssistant = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PAUSE -> {
                    Log.d(TAG, "Pause requested by assistant")
                    isPausedByAssistant = true
                    stopListening()
                    WakeWordDebugManager.updateStatus(WakeWordStatus.Paused)
                }
                ACTION_RESUME -> {
                    Log.d(TAG, "Resume requested by assistant")
                    isPausedByAssistant = false
                    serviceScope.launch {
                        val enabled = settingsRepository.currentWakeWordEnabled()
                        val word = settingsRepository.currentWakeWord()
                        if (enabled) startListening(word)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as LmDroidApplication).container
        settingsRepository = container.settingsRepository
        voskRepository = container.voskRepository
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
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
                if (enabled && !isPausedByAssistant) {
                    startListening(word)
                } else if (!enabled) {
                    stopListening()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListening(word: String) {
        listeningJob?.cancel()
        listeningJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (model == null) {
                    WakeWordDebugManager.updateStatus(WakeWordStatus.LoadingModel)
                    model = voskRepository.getModel()
                }
                val m = model ?: return@launch

                WakeWordDebugManager.updateStatus(WakeWordStatus.Listening)

                val normalizedWord = normalizeWakeWord(word)
                // Remove grammar restriction to see all recognized text for debugging.
                // The dictionary will now attempt to match any word it knows.
                val recognizer = Recognizer(m, 16000.0f)

                try {
                    val bufferSize = 8000
                    val minBufSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    
                    if (ActivityCompat.checkSelfPermission(this@WakeWordService, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        WakeWordDebugManager.updateStatus(WakeWordStatus.Error("Mic permission missing"))
                        return@launch
                    }
                    
                    val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize.coerceAtLeast(bufferSize))

                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        WakeWordDebugManager.updateStatus(WakeWordStatus.Error("Mic init failed"))
                        return@launch
                    }

                    try {
                        recorder.startRecording()
                        val buffer = ShortArray(bufferSize)
                        while (listeningJob?.isActive == true) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                if (recognizer.acceptWaveForm(buffer, read)) {
                                    val result = recognizer.result
                                    val text = parseVoskText(result)
                                    WakeWordDebugManager.updateLastResult(text)
                                    if (text.contains(normalizedWord) || text.contains(word)) launchAssistant()
                                } else {
                                    val partial = recognizer.partialResult
                                    val text = parseVoskPartial(partial)
                                    if (text.isNotBlank()) WakeWordDebugManager.updateLastResult(text)
                                    if (text.contains(normalizedWord) || text.contains(word)) launchAssistant()
                                }
                            }
                        }
                        recorder.stop()
                    } finally {
                        recorder.release()
                    }
                } finally {
                    recognizer.close()
                }
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
        if (!isPausedByAssistant) {
            WakeWordDebugManager.updateStatus(WakeWordStatus.Idle)
        }
    }

    private fun parseVoskText(json: String): String {
        return try {
            JSONObject(json).getString("text")
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).getString("partial")
        } catch (e: Exception) {
            ""
        }
    }

    private fun normalizeWakeWord(word: String): String {
        // Japanese model dictionary usually has Katakana or Kanji. 
        // Vosk dictionary for this model specifically uses Katakana for words like "テスト".
        // Hiragana-to-Katakana mapping for common characters.
        val sb = StringBuilder()
        for (char in word) {
            if (char in '\u3041'..'\u3096') {
                sb.append(char + 0x60)
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }

    private fun launchAssistant() {
        serviceScope.launch(Dispatchers.Main) {
            LmDroidVoiceInteractionService.trigger(this@WakeWordService)
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
        unregisterReceiver(receiver)
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wake_word_service"
        const val EXTRA_WAKE_WORD_TRIGGERED = "wake_word_triggered"

        const val ACTION_PAUSE = "com.suzuri.lmdroid.ACTION_PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.suzuri.lmdroid.ACTION_RESUME_WAKE_WORD"
    }
}
