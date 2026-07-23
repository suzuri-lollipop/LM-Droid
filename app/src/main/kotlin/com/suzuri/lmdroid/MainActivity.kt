package com.suzuri.lmdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.chat.ChatScreen
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.navigation.Screen
import com.suzuri.lmdroid.ui.settings.SettingsScreen
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.theme.LmDroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LmDroidApplication).container
        val viewModelFactory = ViewModelFactory(container)

        setContent {
            LmDroidTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LmDroidApp(viewModelFactory)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LmDroidApp(viewModelFactory: ViewModelFactory) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Chat) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentScreen == Screen.Chat) {
                            stringResource(R.string.chat_title)
                        } else {
                            stringResource(R.string.settings_title)
                        },
                    )
                },
                navigationIcon = {
                    if (currentScreen == Screen.Settings) {
                        IconButton(onClick = { currentScreen = Screen.Chat }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                actions = {
                    if (currentScreen == Screen.Chat) {
                        IconButton(onClick = { currentScreen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (currentScreen) {
            Screen.Chat -> {
                val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = { currentScreen = Screen.Settings },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            Screen.Settings -> {
                val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
                SettingsScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}
