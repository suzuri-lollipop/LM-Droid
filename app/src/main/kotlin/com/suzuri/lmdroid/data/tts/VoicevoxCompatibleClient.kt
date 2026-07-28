package com.suzuri.lmdroid.data.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin client for VOICEVOX Engine's HTTP API (https://voicevox.github.io) — also covers AivisSpeech
 * Engine, which is deliberately API-compatible with it. Synthesis is a two-step dance: POST
 * /audio_query turns text into an intermediate "audio query" JSON (phoneme/prosody info), which
 * POST /synthesis then renders to a WAV file. This client never needs to understand that JSON's
 * own shape — it's forwarded verbatim from the first call's response body into the second call's
 * request body.
 */
class VoicevoxCompatibleClient(private val okHttpClient: OkHttpClient) {

    suspend fun synthesize(baseUrl: String, text: String, speakerId: Int): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val audioQueryJson = requestAudioQuery(baseUrl, text, speakerId)
                    ?: return@withContext Result.failure(IOException("audio_query returned an empty body"))
                val audioBytes = requestSynthesis(baseUrl, audioQueryJson, speakerId)
                    ?: return@withContext Result.failure(IOException("synthesis returned an empty body"))
                Result.success(audioBytes)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /** GET /speakers exists on both engines and is enough of a reachability check for "接続テスト" — the response isn't parsed. */
    suspend fun testConnection(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/speakers").get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "testConnection: /speakers returned HTTP ${response.code}")
                    Result.failure(IOException("HTTP ${response.code}"))
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun requestAudioQuery(baseUrl: String, text: String, speakerId: Int): String? {
        val url = "${baseUrl.trimEnd('/')}/audio_query".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .addQueryParameter("speaker", speakerId.toString())
            .build()
        // No request body — audio_query takes its input entirely as query parameters, but OkHttp
        // still requires a (possibly empty) RequestBody for a POST.
        val request = Request.Builder().url(url).post("".toRequestBody(null)).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "audio_query returned HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                return null
            }
            return response.body?.string()
        }
    }

    private fun requestSynthesis(baseUrl: String, audioQueryJson: String, speakerId: Int): ByteArray? {
        val url = "${baseUrl.trimEnd('/')}/synthesis".toHttpUrl().newBuilder()
            .addQueryParameter("speaker", speakerId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .post(audioQueryJson.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "synthesis returned HTTP ${response.code}")
                return null
            }
            return response.body?.bytes()
        }
    }

    companion object {
        private const val TAG = "VoicevoxCompatibleClient"

        // VOICEVOX Engine's own documented default local port (AivisSpeech Engine defaults to
        // :10101 instead) — just a starting point prefilled for a newly created profile; the user
        // is expected to point it at whichever engine/port they actually run.
        const val DEFAULT_BASE_URL = "http://127.0.0.1:50021"
    }
}
