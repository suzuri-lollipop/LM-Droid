package com.suzuri.lmdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
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
import com.suzuri.lmdroid.ui.history.HistoryScreen
import com.suzuri.lmdroid.ui.history.HistoryViewModel
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

    // Hoisted here (not inside the Scaffold content lambda) so the top bar's actions can also
    // reach them — e.g. tapping "new chat" from the History screen's top bar.
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            Screen.Chat -> stringResource(R.string.chat_title)
                            Screen.Settings -> stringResource(R.string.settings_title)
                            Screen.History -> stringResource(R.string.history_title)
                        },
                    )
                },
                navigationIcon = {
                    when (currentScreen) {
                        Screen.Chat -> {
                            IconButton(onClick = { currentScreen = Screen.History }) {
                                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.history_title))
                            }
                        }
                        Screen.Settings, Screen.History -> {
                            IconButton(onClick = { currentScreen = Screen.Chat }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                    }
                },
                actions = {
                    when (currentScreen) {
                        Screen.Chat -> {
                            IconButton(onClick = { chatViewModel.startNewConversation() }) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.history_new_conversation),
                                )
                            }
                            IconButton(onClick = { currentScreen = Screen.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                            }
                        }
                        Screen.History -> {
                            IconButton(
                                onClick = {
                                    chatViewModel.startNewConversation()
                                    currentScreen = Screen.Chat
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.history_new_conversation),
                                )
                            }
                        }
                        Screen.Settings -> Unit
                    }
                },
            )
        },
    ) { innerPadding ->
        when (currentScreen) {
            Screen.Chat -> {
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = { currentScreen = Screen.Settings },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            Screen.Settings -> {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            Screen.History -> {
                HistoryScreen(
                    viewModel = historyViewModel,
                    onSelectConversation = { id ->
                        chatViewModel.switchToConversation(id)
                        currentScreen = Screen.Chat
                    },
                    onDeleteConversation = { id ->
                        // If the deleted conversation is the one ChatViewModel currently has
                        // open, redirect it to a fresh conversation first — otherwise sending a
                        // message there would try to insert against a now-nonexistent
                        // conversationId and violate the messages table's foreign key.
                        if (chatViewModel.currentConversationId() == id) {
                            chatViewModel.startNewConversation()
                        }
                        historyViewModel.onDeleteConversation(id)
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}
