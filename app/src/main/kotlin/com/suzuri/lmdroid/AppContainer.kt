package com.suzuri.lmdroid

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.suzuri.lmdroid.data.attachment.AttachmentFileStore
import com.suzuri.lmdroid.data.attachment.AudioRecorder
import com.suzuri.lmdroid.data.db.AppDatabase
import com.suzuri.lmdroid.data.location.DeviceLocationProvider
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.repository.ApiProfileRepository
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.repository.SystemPromptRepository
import com.suzuri.lmdroid.data.settings.ApiKeyCipher
import com.suzuri.lmdroid.data.settings.SettingsExporter
import com.suzuri.lmdroid.data.settings.SettingsImporter
import com.suzuri.lmdroid.data.settings.SettingsRepository
import com.suzuri.lmdroid.data.tts.AssistSpeechPlayer
import com.suzuri.lmdroid.data.tts.OnDeviceSpeechSynthesizer
import com.suzuri.lmdroid.data.tts.VoicevoxCompatibleClient
import com.suzuri.lmdroid.data.websearch.BraveSearchClient
import com.suzuri.lmdroid.data.websearch.WebPageFetcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container. With ~6 long-lived singletons and 2 ViewModels this app is
 * small enough that a DI framework like Hilt would add more ceremony (extra KSP processor,
 * annotations) than it saves — see the plan doc for the tradeoff being accepted.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        // kotlinx.serialization defaults to omitting fields whose value equals the Kotlin
        // default (e.g. "stream": true, "max_tokens": 256) — which silently dropped "stream"
        // from every request since it's always passed as true, disabling server-side streaming.
        encodeDefaults = true
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message -> Log.d("OkHttpWire", message) }
        .apply { level = HttpLoggingInterceptor.Level.HEADERS }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // readTimeout is a per-read "gap between chunks" timeout, not a cap on total stream
        // duration, so a generous value still allows an arbitrarily long, slow-but-progressing
        // stream — it only fires if the server goes completely silent, which otherwise left the
        // app waiting forever with no error. callTimeout (the total-duration cap) stays disabled
        // since a long response that's actively streaming tokens must not be cut off.
        // Increased to 5 minutes to accommodate extremely slow/non-streaming local LLM servers.
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        // A network interceptor (not an application interceptor) so this shows the request
        // exactly as it goes over the wire, including headers OkHttp adds automatically
        // (Content-Length, Host, Accept-Encoding, etc.) — useful for diffing against curl.
        .addNetworkInterceptor(loggingInterceptor)
        .build()

    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        // No migrations written yet (still early/local development) — just recreate the DB on
        // a schema version bump rather than crash. This wipes locally stored chat history once
        // per schema change, which is an acceptable tradeoff at this stage.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private val apiKeyCipher = ApiKeyCipher()

    val settingsRepository = SettingsRepository(appContext, apiKeyCipher, database.apiProfileDao())

    val settingsExporter = SettingsExporter(database.apiProfileDao(), database.apiModelDao(), database.systemPromptDao(), settingsRepository)

    val settingsImporter = SettingsImporter(database.apiProfileDao(), database.apiModelDao(), database.systemPromptDao(), settingsRepository)

    val openAiApiClient = OpenAiApiClient(okHttpClient, json)

    val apiProfileRepository = ApiProfileRepository(
        database.apiProfileDao(),
        database.apiModelDao(),
        apiKeyCipher,
        openAiApiClient,
    )

    val attachmentFileStore = AttachmentFileStore(appContext)

    val audioRecorder = AudioRecorder(attachmentFileStore)

    val braveSearchClient = BraveSearchClient(okHttpClient, json)

    val webPageFetcher = WebPageFetcher(okHttpClient)

    val deviceLocationProvider = DeviceLocationProvider(appContext)

    val voicevoxCompatibleClient = VoicevoxCompatibleClient(okHttpClient)

    val onDeviceSpeechSynthesizer = OnDeviceSpeechSynthesizer(appContext)

    val assistSpeechPlayer = AssistSpeechPlayer(appContext, settingsRepository, voicevoxCompatibleClient, onDeviceSpeechSynthesizer)

    val systemPromptRepository = SystemPromptRepository(database.systemPromptDao(), settingsRepository)

    val conversationRepository = ConversationRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        messageAttachmentDao = database.messageAttachmentDao(),
        folderDao = database.folderDao(),
        settingsRepository = settingsRepository,
        openAiApiClient = openAiApiClient,
        attachmentFileStore = attachmentFileStore,
        braveSearchClient = braveSearchClient,
        webPageFetcher = webPageFetcher,
        deviceLocationProvider = deviceLocationProvider,
        systemPromptRepository = systemPromptRepository,
        json = json,
    )
}
