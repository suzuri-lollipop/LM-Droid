package com.suzuri.lmdroid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.suzuri.lmdroid.data.character.CharacterModelType
import com.suzuri.lmdroid.service.WakeWordService
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.assist.AssistScreen
import com.suzuri.lmdroid.ui.assist.AssistViewModel
import com.suzuri.lmdroid.ui.theme.LmDroidTheme
import kotlinx.coroutines.runBlocking

/**
 * Entry point for the system assist gesture (see AndroidManifest's ACTION_ASSIST filter, and
 * Settings → アシスタント for registering this app as the device's assist app). Deliberately a
 * separate Activity from MainActivity rather than a mode/flag on it: assist sessions are meant to
 * appear as a small overlay on top of whatever app was already open (see Theme.LmDroid.Assist),
 * not switch full-screen into this app's own task.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var viewModel: AssistViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // The novel-game stage hosts a GLSurfaceView, whose child surface is dropped entirely
        // while this window is translucent (the manifest theme, needed for the bottom-sheet
        // fallback). With a character configured the stage covers the full screen with its own
        // (GL-drawn) background anyway, so switch to the non-translucent variant theme before
        // super.onCreate applies the manifest theme. The DataStore read is a tiny file; blocking
        // onCreate on it beats a one-frame flicker.
        val container = (application as LmDroidApplication).container
        val characterSettings = runBlocking { container.settingsRepository.currentCharacterSettings() }
        if (characterSettings.modelType != CharacterModelType.NONE) {
            setTheme(R.style.Theme_LmDroid_Assist_Stage)
        }

        super.onCreate(savedInstanceState)

        // Triggered while the device is asleep/locked (wake word, assist gesture, headset
        // button), this activity otherwise launches behind the keyguard and doesn't reach
        // Lifecycle.State.STARTED until the user manually unlocks — confirmed via on-device
        // logcat, leaving AssistScreen stuck showing "準備中" for however long that takes.
        // Showing over the keyguard (without dismissing it — the device stays locked) and
        // waking the screen restores hands-free lock-screen usage instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        Log.d("AssistActivity", "onCreate: intent=$intent")
        Log.d("AssistActivity", "onCreate: Pausing wake word")
        sendBroadcast(Intent(WakeWordService.ACTION_PAUSE))

        val viewModelFactory = ViewModelFactory(container)
        viewModel = ViewModelProvider(this, viewModelFactory)[AssistViewModel::class.java]

        setContent {
            LmDroidTheme {
                AssistScreen(
                    viewModel = viewModel,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("AssistActivity", "onNewIntent: intent=$intent")
        Log.d("AssistActivity", "onNewIntent: Pausing wake word (just in case)")
        sendBroadcast(Intent(WakeWordService.ACTION_PAUSE))
        // If the activity is already on screen, singleInstance means it won't be recreated.
        // We call onRetry() to reset the state and increment triggerCount, which signals
        // AssistScreen's LaunchedEffect to start listening again.
        viewModel.onRetry()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AssistActivity", "onDestroy: Resuming wake word")
        sendBroadcast(Intent(WakeWordService.ACTION_RESUME))
        // Belt-and-suspenders on top of AssistViewModel.onCleared: guarantees a reply whose
        // audio hadn't started yet can never begin speaking after the overlay is gone.
        ((application as LmDroidApplication).container).assistSpeechPlayer.stop()
    }
}
