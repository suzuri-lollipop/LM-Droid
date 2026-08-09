package com.suzuri.lmdroid

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.suzuri.lmdroid.service.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LmDroidApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        applicationScope.launch {
            container.settingsRepository.wakeWordEnabled.collect { enabled ->
                // Delay service start slightly to ensure the app is considered "in foreground"
                // during cold start, avoiding SecurityException for microphone usage.
                if (enabled) kotlinx.coroutines.delay(1000)
                
                val intent = Intent(this@LmDroidApplication, WakeWordService::class.java)
                if (enabled) {
                    val hasMicPermission = ContextCompat.checkSelfPermission(
                        this@LmDroidApplication,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasMicPermission) {
                        startForegroundService(intent)
                    } else {
                        // If enabled but no permission, we can't start the service.
                        // Setting screen handles the actual toggle + permission request.
                        android.util.Log.w("LmDroidApplication", "WakeWordService enabled but RECORD_AUDIO permission not granted")
                    }
                } else {
                    stopService(intent)
                }
            }
        }
    }
}
