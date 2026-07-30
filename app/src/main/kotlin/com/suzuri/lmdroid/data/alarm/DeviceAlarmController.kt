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

    /**
     * Null if no clock app can handle it (checked via `resolveActivity`, without launching
     * anything); otherwise a function that actually opens the alarm screen. Callers (the
     * tool-calling round trip in ConversationRepository) invoke the returned function only once
     * the reply describing the action has been fully shown, so the clock app doesn't interrupt the
     * screen before the user has seen/heard why.
     */
    fun prepareSetAlarm(hour: Int, minute: Int, label: String?): (() -> Unit)? {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return null
        return { launch(intent, "setAlarm") }
    }

    /** Same as [prepareSetAlarm], but for `ACTION_SET_TIMER`. */
    fun prepareSetTimer(seconds: Int, label: String?): (() -> Unit)? {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return null
        return { launch(intent, "setTimer") }
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
