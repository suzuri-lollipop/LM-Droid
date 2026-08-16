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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suzuri.lmdroid.data.db.ApiProfileEntity
import com.suzuri.lmdroid.data.settings.WrongPasswordException
import com.suzuri.lmdroid.data.settings.isEncryptedSettingsExport
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.chat.ChatScreen
import com.suzuri.lmdroid.ui.chat.ChatViewModel
import com.suzuri.lmdroid.ui.history.HistoryScreen
import com.suzuri.lmdroid.ui.history.HistoryViewModel
import com.suzuri.lmdroid.ui.navigation.Screen
import com.suzuri.lmdroid.ui.settings.AlarmSettingsScreen
import com.suzuri.lmdroid.ui.settings.AlarmSettingsViewModel
import com.suzuri.lmdroid.ui.settings.ApiProfileListScreen
import com.suzuri.lmdroid.ui.settings.ApiProfileListViewModel
import com.suzuri.lmdroid.ui.settings.AssistantSettingsScreen
import com.suzuri.lmdroid.ui.settings.AssistantSettingsViewModel
import com.suzuri.lmdroid.ui.settings.BraveSearchProfileEditScreen
import com.suzuri.lmdroid.ui.settings.BraveSearchProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.CharacterSettingsScreen
import com.suzuri.lmdroid.ui.settings.CharacterSettingsViewModel
import com.suzuri.lmdroid.ui.settings.ImageGenerationProfileEditScreen
import com.suzuri.lmdroid.ui.settings.ImageGenerationProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.LocationSettingsScreen
import com.suzuri.lmdroid.ui.settings.LocationSettingsViewModel
import com.suzuri.lmdroid.ui.settings.MessagingSettingsScreen
import com.suzuri.lmdroid.ui.settings.MessagingSettingsViewModel
import com.suzuri.lmdroid.ui.settings.MusicSettingsScreen
import com.suzuri.lmdroid.ui.settings.MusicSettingsViewModel
import com.suzuri.lmdroid.ui.settings.NotesSettingsScreen
import com.suzuri.lmdroid.ui.settings.NotesSettingsViewModel
import com.suzuri.lmdroid.ui.settings.OpenAiCompatibleScreen
import com.suzuri.lmdroid.ui.settings.OpenAiTtsProfileEditScreen
import com.suzuri.lmdroid.ui.settings.OpenAiTtsProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.SettingsExportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsImportViewModel
import com.suzuri.lmdroid.ui.settings.SettingsRoute
import com.suzuri.lmdroid.ui.settings.SettingsRootScreen
import com.suzuri.lmdroid.ui.settings.SettingsViewModel
import com.suzuri.lmdroid.ui.settings.SkillEditScreen
import com.suzuri.lmdroid.ui.settings.SkillEditViewModel
import com.suzuri.lmdroid.ui.settings.SkillListScreen
import com.suzuri.lmdroid.ui.settings.SkillListViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptEditScreen
import com.suzuri.lmdroid.ui.settings.SystemPromptEditViewModel
import com.suzuri.lmdroid.ui.settings.SystemPromptListScreen
import com.suzuri.lmdroid.ui.settings.SystemPromptListViewModel
import com.suzuri.lmdroid.ui.settings.SystemSettingsScreen
import com.suzuri.lmdroid.ui.settings.SystemSettingsViewModel
import com.suzuri.lmdroid.ui.settings.VoiceSettingsScreen
import com.suzuri.lmdroid.ui.settings.VoiceSettingsViewModel
import com.suzuri.lmdroid.ui.settings.VoicevoxProfileEditScreen
import com.suzuri.lmdroid.ui.settings.VoicevoxProfileEditViewModel
import com.suzuri.lmdroid.ui.settings.WebSearchSettingsScreen
import com.suzuri.lmdroid.ui.settings.WebSearchSettingsViewModel
import com.suzuri.lmdroid.ui.settings.YoutubeDataApiProfileEditScreen
import com.suzuri.lmdroid.ui.settings.YoutubeDataApiProfileEditViewModel
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
    // Which profile OpenAiCompatibleScreen/BraveSearchProfileEditScreen/VoicevoxProfileEditScreen
    // is editing — only meaningful while settingsRoute is OpenAiCompatible, BraveSearchProfile, or
    // VoicevoxProfile; always set to a real, already-created profile id just before navigating
    // there (profiles are created immediately when added, so there's no "editing a new/unsaved
    // profile" state to represent here).
    var editingProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Same idea as editingProfileId, but for SystemPromptEdit — always set to a real,
    // already-created prompt id just before navigating there (prompts are created immediately
    // when added, same as API profiles).
    var editingSystemPromptId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Same idea, but for SkillEdit.
    var editingSkillId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Hoisted here (not inside the Scaffold content lambda) so the top bar's actions can also
    // reach them — e.g. tapping "new chat" from the history drawer.
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val braveSearchProfileEditViewModel: BraveSearchProfileEditViewModel = viewModel(factory = viewModelFactory)
    val voicevoxProfileEditViewModel: VoicevoxProfileEditViewModel = viewModel(factory = viewModelFactory)
    val openAiTtsProfileEditViewModel: OpenAiTtsProfileEditViewModel = viewModel(factory = viewModelFactory)
    val youtubeDataApiProfileEditViewModel: YoutubeDataApiProfileEditViewModel = viewModel(factory = viewModelFactory)
    val imageGenerationProfileEditViewModel: ImageGenerationProfileEditViewModel = viewModel(factory = viewModelFactory)
    val apiProfileListViewModel: ApiProfileListViewModel = viewModel(factory = viewModelFactory)
    val systemSettingsViewModel: SystemSettingsViewModel = viewModel(factory = viewModelFactory)
    val webSearchSettingsViewModel: WebSearchSettingsViewModel = viewModel(factory = viewModelFactory)
    val voiceSettingsViewModel: VoiceSettingsViewModel = viewModel(factory = viewModelFactory)
    val locationSettingsViewModel: LocationSettingsViewModel = viewModel(factory = viewModelFactory)
    val alarmSettingsViewModel: AlarmSettingsViewModel = viewModel(factory = viewModelFactory)
    val notesSettingsViewModel: NotesSettingsViewModel = viewModel(factory = viewModelFactory)
    val messagingSettingsViewModel: MessagingSettingsViewModel = viewModel(factory = viewModelFactory)
    val musicSettingsViewModel: MusicSettingsViewModel = viewModel(factory = viewModelFactory)
    val systemPromptListViewModel: SystemPromptListViewModel = viewModel(factory = viewModelFactory)
    val systemPromptEditViewModel: SystemPromptEditViewModel = viewModel(factory = viewModelFactory)
    val skillListViewModel: SkillListViewModel = viewModel(factory = viewModelFactory)
    val skillEditViewModel: SkillEditViewModel = viewModel(factory = viewModelFactory)
    val assistantSettingsViewModel: AssistantSettingsViewModel = viewModel(factory = viewModelFactory)
    val characterSettingsViewModel: CharacterSettingsViewModel = viewModel(factory = viewModelFactory)
    val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory)
    val settingsExportViewModel: SettingsExportViewModel = viewModel(factory = viewModelFactory)
    val settingsImportViewModel: SettingsImportViewModel = viewModel(factory = viewModelFactory)

    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportFailedMessage = stringResource(R.string.settings_export_failure)
    val exportSucceededMessage = stringResource(R.string.settings_export_success)
    // The password dialog shown before the file picker (below) — exporting first asks for a
    // password because it decides whether API keys travel in the file at all.
    var showExportDialog by remember { mutableStateOf(false) }
    // The password confirmed in that dialog — empty string means the plain, no-password backup
    // format. Plain remember (deliberately NOT rememberSaveable) so the password never enters
    // the saved-instance-state Bundle.
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    // CreateDocument hands back a content:// Uri already created at the location the user picked
    // (e.g. via the system "Save As" dialog) — writing is just a normal ContentResolver stream.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val result = runCatching {
                val yaml = settingsExportViewModel.exportToYaml(password ?: "")
                context.contentResolver.openOutputStream(uri)?.use { it.write(yaml.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to open $uri for writing")
            }
            Toast.makeText(context, if (result.isSuccess) exportSucceededMessage else exportFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    val importScope = rememberCoroutineScope()
    val importFailedMessage = stringResource(R.string.settings_import_failure)
    val importWrongPasswordMessage = stringResource(R.string.settings_import_wrong_password)
    val importSucceededMessage = stringResource(R.string.settings_import_success)
    // Importing overwrites several app-wide settings (see SettingsImporter), so the picked file is
    // held here and only actually read/applied once the user confirms the dialog below — rather
    // than acting the instant a file is picked, in case the wrong file was chosen by mistake.
    var pendingImportUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Whether the picked file is a password-protected export — sniffed at pick time (via the
    // `format` marker) so the confirmation dialog knows whether to show a password field.
    var pendingImportRequiresPassword by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val requiresPassword = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.let { isEncryptedSettingsExport(it) }
                ?: false
        }.getOrDefault(false)
        pendingImportRequiresPassword = requiresPassword
        pendingImportUri = uri
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
        // Swipe-to-open only belongs on the Chat screen — on Settings the same left-to-right
        // swipe kept summoning the conversation history drawer by accident.
        gesturesEnabled = currentScreen != Screen.Settings,
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
                                    SettingsRoute.BraveSearchProfile -> stringResource(R.string.settings_brave_search_title)
                                    SettingsRoute.VoicevoxProfile -> stringResource(R.string.settings_voicevox_title)
                                    SettingsRoute.OpenAiTtsProfile -> stringResource(R.string.settings_openai_tts_title)
                                    SettingsRoute.YoutubeDataApiProfile -> stringResource(R.string.settings_youtube_data_api_title)
                                    SettingsRoute.ImageGenerationProfile -> stringResource(R.string.settings_image_generation_title)
                                    SettingsRoute.System -> stringResource(R.string.settings_system_category_title)
                                    SettingsRoute.WebSearch -> stringResource(R.string.settings_websearch_category_title)
                                    SettingsRoute.Voice -> stringResource(R.string.settings_voice_category_title)
                                    SettingsRoute.Location -> stringResource(R.string.settings_location_category_title)
                                    SettingsRoute.Alarm -> stringResource(R.string.settings_alarm_category_title)
                                    SettingsRoute.Notes -> stringResource(R.string.settings_notes_category_title)
                                    SettingsRoute.Messaging -> stringResource(R.string.settings_messaging_category_title)
                                    SettingsRoute.Music -> stringResource(R.string.settings_music_category_title)
                                    SettingsRoute.SystemPromptList -> stringResource(R.string.settings_system_prompt_category_title)
                                    SettingsRoute.SystemPromptEdit -> stringResource(R.string.settings_system_prompt_category_title)
                                    SettingsRoute.SkillList -> stringResource(R.string.settings_skill_category_title)
                                    SettingsRoute.SkillEdit -> stringResource(R.string.settings_skill_category_title)
                                    SettingsRoute.Assistant -> stringResource(R.string.settings_assistant_category_title)
                                    SettingsRoute.Character -> stringResource(R.string.settings_character_category_title)
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
                        onManageSkills = {
                            settingsRoute = SettingsRoute.SkillList
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
                                onNavigateToVoice = { settingsRoute = SettingsRoute.Voice },
                                onNavigateToLocation = { settingsRoute = SettingsRoute.Location },
                                onNavigateToAlarm = { settingsRoute = SettingsRoute.Alarm },
                                onNavigateToNotes = { settingsRoute = SettingsRoute.Notes },
                                onNavigateToMessaging = { settingsRoute = SettingsRoute.Messaging },
                                onNavigateToMusic = { settingsRoute = SettingsRoute.Music },
                                onNavigateToSystemPrompts = { settingsRoute = SettingsRoute.SystemPromptList },
                                onNavigateToSkills = { settingsRoute = SettingsRoute.SkillList },
                                onNavigateToAssistant = { settingsRoute = SettingsRoute.Assistant },
                                onNavigateToCharacter = { settingsRoute = SettingsRoute.Character },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.ApiSettings -> {
                            ApiProfileListScreen(
                                viewModel = apiProfileListViewModel,
                                onNavigateToProfile = { id, providerType ->
                                    editingProfileId = id
                                    settingsRoute = when (providerType) {
                                        ApiProfileEntity.PROVIDER_BRAVE_SEARCH -> SettingsRoute.BraveSearchProfile
                                        ApiProfileEntity.PROVIDER_VOICEVOX_COMPATIBLE -> SettingsRoute.VoicevoxProfile
                                        ApiProfileEntity.PROVIDER_OPENAI_TTS -> SettingsRoute.OpenAiTtsProfile
                                        ApiProfileEntity.PROVIDER_YOUTUBE_DATA_API -> SettingsRoute.YoutubeDataApiProfile
                                        ApiProfileEntity.PROVIDER_DASHSCOPE,
                                        ApiProfileEntity.PROVIDER_STABLE_DIFFUSION,
                                        ApiProfileEntity.PROVIDER_COMFYUI,
                                        ApiProfileEntity.PROVIDER_LOCAL -> SettingsRoute.ImageGenerationProfile
                                        else -> SettingsRoute.OpenAiCompatible
                                    }
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
                        SettingsRoute.BraveSearchProfile -> {
                            val id = editingProfileId
                            if (id != null) {
                                BraveSearchProfileEditScreen(
                                    viewModel = braveSearchProfileEditViewModel,
                                    profileId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.VoicevoxProfile -> {
                            val id = editingProfileId
                            if (id != null) {
                                VoicevoxProfileEditScreen(
                                    viewModel = voicevoxProfileEditViewModel,
                                    profileId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.OpenAiTtsProfile -> {
                            val id = editingProfileId
                            if (id != null) {
                                OpenAiTtsProfileEditScreen(
                                    viewModel = openAiTtsProfileEditViewModel,
                                    profileId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.YoutubeDataApiProfile -> {
                            val id = editingProfileId
                            if (id != null) {
                                YoutubeDataApiProfileEditScreen(
                                    viewModel = youtubeDataApiProfileEditViewModel,
                                    profileId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.ImageGenerationProfile -> {
                            val id = editingProfileId
                            if (id != null) {
                                ImageGenerationProfileEditScreen(
                                    viewModel = imageGenerationProfileEditViewModel,
                                    profileId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.System -> {
                            SystemSettingsScreen(
                                viewModel = systemSettingsViewModel,
                                onExportSettings = { showExportDialog = true },
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
                        SettingsRoute.Voice -> {
                            VoiceSettingsScreen(
                                viewModel = voiceSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Location -> {
                            LocationSettingsScreen(
                                viewModel = locationSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Alarm -> {
                            AlarmSettingsScreen(
                                viewModel = alarmSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Notes -> {
                            NotesSettingsScreen(
                                viewModel = notesSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Messaging -> {
                            MessagingSettingsScreen(
                                viewModel = messagingSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Music -> {
                            MusicSettingsScreen(
                                viewModel = musicSettingsViewModel,
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
                        SettingsRoute.SkillList -> {
                            SkillListScreen(
                                viewModel = skillListViewModel,
                                onNavigateToSkill = { id ->
                                    editingSkillId = id
                                    settingsRoute = SettingsRoute.SkillEdit
                                },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.SkillEdit -> {
                            val id = editingSkillId
                            if (id != null) {
                                SkillEditScreen(
                                    viewModel = skillEditViewModel,
                                    skillId = id,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                        SettingsRoute.Assistant -> {
                            AssistantSettingsScreen(
                                viewModel = assistantSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        SettingsRoute.Character -> {
                            CharacterSettingsScreen(
                                viewModel = characterSettingsViewModel,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        var password by remember { mutableStateOf("") }
        var passwordConfirm by remember { mutableStateOf("") }
        val mismatched = passwordConfirm.isNotEmpty() && password != passwordConfirm
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.settings_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_export_password_description))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        label = { Text(stringResource(R.string.settings_export_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        label = { Text(stringResource(R.string.settings_export_password_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = mismatched,
                        supportingText = if (mismatched) {
                            { Text(stringResource(R.string.settings_export_password_mismatch)) }
                        } else {
                            null
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    // A mismatched confirmation is the only invalid state — an empty password is
                    // allowed on purpose (plain backup format without transferable API keys).
                    enabled = !mismatched,
                    onClick = {
                        showExportDialog = false
                        pendingExportPassword = password
                        exportLauncher.launch(EXPORT_FILE_NAME)
                    },
                ) {
                    Text(stringResource(R.string.settings_export_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.chat_edit_cancel))
                }
            },
        )
    }

    pendingImportUri?.let { uri ->
        // Local to the dialog's lifetime — resets to empty every time a new file is picked.
        var importPassword by remember { mutableStateOf("") }
        val requiresPassword = pendingImportRequiresPassword
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_import_confirm_message))
                    if (requiresPassword) {
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            label = { Text(stringResource(R.string.settings_import_password_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !requiresPassword || importPassword.isNotEmpty(),
                    onClick = {
                        pendingImportUri = null
                        importScope.launch {
                            val result = runCatching {
                                val yaml = context.contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.readBytes().toString(Charsets.UTF_8)
                                } ?: error("Unable to open $uri for reading")
                                settingsImportViewModel.importFromYaml(yaml, importPassword.ifEmpty { null }).getOrThrow()
                            }
                            val message = when {
                                result.isSuccess -> importSucceededMessage
                                result.exceptionOrNull() is WrongPasswordException -> importWrongPasswordMessage
                                else -> importFailedMessage
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
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
