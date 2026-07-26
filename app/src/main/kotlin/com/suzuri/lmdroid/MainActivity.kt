package com.suzuri.lmdroid

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.chat.ChatScreen
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.history.HistoryScreen
import com.suzuri.lmdroid.ui.history.HistoryViewModel
import com.suzuri.lmdroid.ui.navigation.Screen
import com.suzuri.lmdroid.ui.settings.ApiProfileListScreen
import com.suzuri.lmdroid.ui.settings.ApiProfileListViewModel
import com.suzuri.lmdroid.ui.settings.AssistantSettingsScreen
import com.suzuri.lmdroid.ui.settings.LocationSettingsScreen
import com.suzuri.lmdroid.ui.settings.LocationSettingsViewModel
import com.suzuri.lmdroid.ui.settings.OpenAiCompatibleScreen
import com.suzuri.lmdroid.ui.settings.SettingsExportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsImportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsRoute
import com.suzuri.lmdroid.ui.settings.SettingsRootScreen
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptEditScreen
import com.suzuri.lmdroid.ui.settings.SystemPromptEditViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptListScreen
import com.suzuri.lmdroid.ui.settings.SystemPromptListViewModel
import com.suzuri.lmdroid.ui.settings.SystemSettingsScreen
import com.suzuri.lmdroid.ui.settings.SystemSettingsViewModel
import com.suzuri.lmdroid.ui.settings.WebSearchSettingsScreen
import com.suzuri.lmdroid.ui.settings.WebSearchSettingsViewModel
import com.suzuri.lmdroid.ui.settings.parent
import com.suzuri.lmdroid.ui.theme.LmDroidTheme
import kotlinx.coroutines.launch

private const val EXPORT_FILE_NAME = "lmdroid-settings.yaml"

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
    // Tracked separately from currentScreen so leaving and re-entering Chat doesn't reset how
    // deep the user was in Settings.
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.Root) }
    // Which profile OpenAiCompatibleScreen is editing — only meaningful while settingsRoute is
    // OpenAiCompatible; always set to a real, already-created profile id just before navigating
    // there (profiles are created immediately when added, so there's no "editing a new/unsaved
    // profile" state to represent here).
    var editingProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Same idea as editingProfileId, but for SystemPromptEdit — always set to a real,
    // already-created prompt id just before navigating there (prompts are created immediately
    // when added, same as API profiles).
    var editingSystemPromptId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Hoisted here (not inside the Scaffold content lambda) so the top bar's actions can also
    // reach them — e.g. tapping "new chat" from the history drawer.
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val apiProfileListViewModel: ApiProfileListViewModel = viewModel(factory = viewModelFactory)
    val systemSettingsViewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)
    val webSearchSettingsViewModel: WebSearchSettingsViewModel = viewModel(factory = viewModelFactory)
    val locationSettingsViewModel: LocationSettingsViewModel = viewModel(factory = viewModelFactory)
    val systemPromptListViewModel: SystemPromptListViewModel = viewModel(factory = viewModelFactory)
    val systemPromptEditViewModel: SystemPromptEditViewModel = viewModel(factory = viewModelFactory)
    val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)
    val settingsExportViewModel: SettingsExportViewModel = viewModel(factory = viewModelFactory)
    val settingsImportViewModel: SettingsImportViewModel = viewModel(factory = viewModelFactory)

    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportFailedMessage = stringResource(R.string.settings_export_failure)
    val exportSucceededMessage = stringResource(R.string.settings_export_success)
    // CreateDocument hands back a content:// Uri already created at the location the user picked
    // (e.g. via the system "Save As" dialog) — writing is just a normal ContentResolver stream.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val result = runCatching {
                val yaml = settingsExportViewModel.exportToYaml()
                context.contentResolver.openOutputStream(uri)?.use { it.write(yaml.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to open $uri for writing")
            }
            Toast.makeText(context, if (result.isSuccess) exportSucceededMessage else exportFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    val importScope = rememberCoroutineScope()
    val importFailedMessage = stringResource(R.string.settings_import_failure)
    val importSucceededMessage = stringResource(R.string.settings_import_success)
    // Importing overwrites several app-wide settings (see SettingsImporter), so the picked file is
    // held here and only actually read/applied once the user confirms the dialog below — rather
    // than acting the instant a file is picked, in case the wrong file was chosen by mistake.
    var pendingImportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    // Conversation history lives in a slide-out drawer (opened via the hamburger icon on the
    // Chat screen) rather than being a separate full-screen destination.
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // Needed here (not just inside ChatScreen) so the top bar can show the active conversation's
    // title instead of the static app name.
    val chatUiState by chatViewModel.uiState.collectAsState()

    // Makes the system/gesture back button pop one level of the Settings drill-down (like the
    // Android Settings app) instead of leaving the tab or exiting the app. Closing the drawer on
    // back press is already handled automatically by ModalNavigationDrawer itself.
    BackHandler(enabled = currentScreen == Screen.Settings) {
        val parentRoute = settingsRoute.parent()
        if (parentRoute != null) {
            settingsRoute = parentRoute
        } else {
            currentScreen = Screen.Chat
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
                        IconButton(
                            onClick = {
                                chatViewModel.startNewConversation()
                                currentScreen = Screen.Chat
                                drawerScope.launch { drawerState.close() }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.history_new_conversation),
                            )
                        }
                    }
                    HistoryScreen(
                        viewModel = historyViewModel,
                        onSelectConversation = { id ->
                            chatViewModel.switchToConversation(id)
                            currentScreen = Screen.Chat
                            drawerScope.launch { drawerState.close() }
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
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
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
                                    SettingsRoute.System -> stringResource(R.string.settings_system_category_title)
                                    SettingsRoute.WebSearch -> stringResource(R.string.settings_websearch_category_title)
                                    SettingsRoute.Location -> stringResource(R.string.settings_location_category_title)
                                    SettingsRoute.SystemPromptList -> stringResource(R.string.settings_system_prompt_category_title)
                                    SettingsRoute.SystemPromptEdit -> stringResource(R.string.settings_system_prompt_category_title)
                                    SettingsRoute.Assistant -> stringResource(R.string.settings_assistant_category_title)
                                }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        when (currentScreen) {
                            Screen.Chat -> {
                                IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.history_title))
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
                        onManageSystemPrompts = {
                            settingsRoute = SettingsRoute.SystemPromptList
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
                                onNavigateToSystem = { settingsRoute = SettingsRoute.System },
                                onNavigateToWebSearch = { settingsRoute = SettingsRoute.WebSearch },
                                onNavigateToLocation = { settingsRoute = SettingsRoute.Location },
                                onNavigateToSystemPrompts = { settingsRoute = SettingsRoute.SystemPromptList },
                                onNavigateToAssistant = { settingsRoute = SettingsRoute.Assistant },
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
                        SettingsRoute.System -> {
                            SystemSettingsScreen(
                                viewModel = systemSettingsViewModel,
                                onExportSettings = { exportLauncher.launch(EXPORT_FILE_NAME) },
                                onImportSettings = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.WebSearch -> {
                            WebSearchSettingsScreen(
                                viewModel = webSearchSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Location -> {
                            LocationSettingsScreen(
                                viewModel = locationSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.SystemPromptList -> {
                            SystemPromptListScreen(
                                viewModel = systemPromptListViewModel,
                                onNavigateToPrompt = { id ->
                                    editingSystemPromptId = id
                                    settingsRoute = SettingsRoute.SystemPromptEdit
                                },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.SystemPromptEdit -> {
                            val id = editingSystemPromptId
                            if (id != null) {
                                SystemPromptEditScreen(
                                    viewModel = systemPromptEditViewModel,
                                    promptId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.Assistant -> {
                            AssistantSettingsScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    importScope.launch {
                        val result = runCatching {
                            val yaml = context.contentResolver.openInputStream(uri)?.use { stream ->
                                stream.readBytes().toString(Charsets.UTF_8)
                            } ?: error("Unable to open $uri for reading")
                            settingsImportViewModel.importFromYaml(yaml).getOrThrow()
                        }
                        Toast.makeText(context, if (result.isSuccess) importSucceededMessage else importFailedMessage, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.settings_import_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(stringResource(R.string.chat_edit_cancel))
                }
            },
        )
    }
}
