package com.suzuri.lmdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suzuri.lmdroid.LmDroidApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restarts [WakeWordService] when the device finishes booting, if the user had it enabled.
 */
class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext as LmDroidApplication
        scope.launch {
            val enabled = appContext.container.settingsRepository.wakeWordEnabled.first()
            if (enabled) {
                val serviceIntent = Intent(context, WakeWordService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
