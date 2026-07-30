package com.suzuri.lmdroid.data.alarm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log

/**
 * Sets device alarms/timers for the "set_alarm"/"set_timer" tools (see ConversationRepository) via
 * the standard `android.provider.AlarmClock` intents.
 *
 * `EXTRA_SKIP_UI = true` is used to create the alarm/timer silently in the background without
 * bringing the device's clock app to the foreground. This provides a smoother experience
 * where the AI confirms the action and the system handles the setup.
 */
class DeviceAlarmController(private val context: Context) {

    /** True if an alarm-capable app actually received the request. */
    fun setAlarm(hour: Int, minute: Int, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "setAlarm")
    }

    /** True if a timer-capable app actually received the request. */
    fun setTimer(seconds: Int, label: String?): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "setTimer")
    }

    private fun launch(intent: Intent, callSite: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "$callSite: no clock app available to handle $intent", e)
        false
    }

    private companion object {
        const val TAG = "DeviceAlarmController"
    }
}
