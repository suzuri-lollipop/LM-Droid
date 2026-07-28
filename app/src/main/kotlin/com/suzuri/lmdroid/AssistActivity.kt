package com.suzuri.lmdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.assist.AssistScreen
import com.suzuri.lmdroid.ui.assist.AssistViewModel
import com.suzuri.lmdroid.ui.theme.LmDroidTheme

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
        super.onCreate(savedInstanceState)

        val container = (application as LmDroidApplication).container
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
        // If the activity is already on screen, singleInstance means it won't be recreated.
        // We call onRetry() to reset the state and increment triggerCount, which signals
        // AssistScreen's LaunchedEffect to start listening again.
        viewModel.onRetry()
    }
}
