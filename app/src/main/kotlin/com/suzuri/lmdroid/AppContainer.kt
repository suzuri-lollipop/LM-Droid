package com.suzuri.lmdroid

import android.content.Context
import androidx.room.Room
import com.suzuri.lmdroid.data.db.AppDatabase
import com.suzuri.lmdroid.data.network.OpenAiApiClient
import com.suzuri.lmdroid.data.repository.ConversationRepository
import com.suzuri.lmdroid.data.settings.ApiKeyCipher
import com.suzuri.lmdroid.data.settings.SettingsRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container. With ~6 long-lived singletons and 2 ViewModels this app is
 * small enough that a DI framework like Hilt would add more ceremony (extra KSP processor,
 * annotations) than it saves — see the plan doc for the tradeoff being accepted.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Streaming chat responses are long-lived; disable read/call timeouts so a slow
        // model doesn't get cut off mid-stream.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        .build()

    private val apiKeyCipher = ApiKeyCipher()

    val settingsRepository = SettingsRepository(appContext, apiKeyCipher)

    val openAiApiClient = OpenAiApiClient(okHttpClient, json)

    val conversationRepository = ConversationRepository(
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        settingsRepository = settingsRepository,
        openAiApiClient = openAiApiClient,
    )
}
