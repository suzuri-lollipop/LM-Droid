package com.suzuri.lmdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.chat.ChatScreen
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.history.HistoryScreen
import com.suzuri.lmdroid.ui.history.HistoryViewModel
import com.suzuri.lmdroid.ui.navigation.Screen
import com.suzuri.lmdroid.ui.settings.ApiProfileListScreen
import com.suzuri.lmdroid.ui.settings.ApiProfileListViewModel
import com.suzuri.lmdroid.ui.settings.OpenAiCompatibleScreen
import com.suzuri.lmdroid.ui.settings.SettingsRoute
import com.suzuri.lmdroid.ui.settings.SettingsRootScreen
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.settings.parent
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
    // Which level of the Settings drill-down is showing (Root → ApiSettings → OpenAiCompatible).
    // Tracked separately from currentScreen so leaving and re-entering History/Chat doesn't
    // reset how deep the user was in Settings.
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.Root) }
    // Which profile OpenAiCompatibleScreen is editing — only meaningful while settingsRoute is
    // OpenAiCompatible; always set to a real, already-created profile id just before navigating
    // there (profiles are created immediately when added, so there's no "editing a new/unsaved
    // profile" state to represent here).
    var editingProfileId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Hoisted here (not inside the Scaffold content lambda) so the top bar's actions can also
    // reach them — e.g. tapping "new chat" from the History screen's top bar.
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val apiProfileListViewModel: ApiProfileListViewModel = viewModel(factory = viewModelFactory)
    val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)

    // Needed here (not just inside ChatScreen) so the top bar can show the active conversation's
    // title instead of the static app name.
    val chatUiState by chatViewModel.uiState.collectAsState()

    // Makes the system/gesture back button pop one level of the Settings drill-down (like the
    // Android Settings app) instead of leaving the tab or exiting the app.
    BackHandler(enabled = currentScreen == Screen.Settings) {
        val parentRoute = settingsRoute.parent()
        if (parentRoute != null) {
            settingsRoute = parentRoute
        } else {
            currentScreen = Screen.Chat
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            // Falls back to the app name only until the conversation's title
                            // (either the "新しい会話" default or the LLM-generated one) loads.
                            Screen.Chat -> chatUiState.conversationTitle.ifBlank { stringResource(R.string.chat_title) }
                            Screen.Settings -> when (settingsRoute) {
                                SettingsRoute.Root -> stringResource(R.string.settings_title)
                                SettingsRoute.ApiSettings -> stringResource(R.string.settings_api_category_title)
                                SettingsRoute.OpenAiCompatible -> stringResource(R.string.settings_openai_compatible_title)
                            }
                            Screen.History -> stringResource(R.string.history_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when (currentScreen) {
                        Screen.Chat -> {
                            IconButton(onClick = { currentScreen = Screen.History }) {
                                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.history_title))
                            }
                        }
                        Screen.Settings -> {
                            IconButton(
                                onClick = {
                                    val parentRoute = settingsRoute.parent()
                                    if (parentRoute != null) {
                                        settingsRoute = parentRoute
                                    } else {
                                        currentScreen = Screen.Chat
                                    }
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        }
                        Screen.History -> {
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
                            IconButton(
                                onClick = {
                                    settingsRoute = SettingsRoute.Root
                                    currentScreen = Screen.Settings
                                },
                            ) {
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
                    onNavigateToSettings = {
                        // Goes to the profile list rather than a specific profile's edit form —
                        // there may be no profile yet (or none selected), so there's no single
                        // "the" profile to deep-link straight to anymore.
                        settingsRoute = SettingsRoute.ApiSettings
                        currentScreen = Screen.Settings
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            Screen.Settings -> {
                when (settingsRoute) {
                    SettingsRoute.Root -> {
                        SettingsRootScreen(
                            onNavigateToApiSettings = { settingsRoute = SettingsRoute.ApiSettings },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                    SettingsRoute.ApiSettings -> {
                        ApiProfileListScreen(
                            viewModel = apiProfileListViewModel,
                            onNavigateToProfile = { id ->
                                editingProfileId = id
                                settingsRoute = SettingsRoute.OpenAiCompatible
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                    SettingsRoute.OpenAiCompatible -> {
                        val id = editingProfileId
                        if (id != null) {
                            OpenAiCompatibleScreen(
                                viewModel = settingsViewModel,
                                profileId = id,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }
                }
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
