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
                val audioBytes = requestSynthesis(baseUrl, audioQueryJson, speakerId, text)
                    ?: return@withContext Result.failure(IOException("synthesis returned an empty body"))
                Result.success(audioBytes)
            } catch (e: IOException) {
                Log.w(TAG, "synthesize: network error for speakerId=$speakerId, text=\"${text.take(LOGGED_TEXT_LENGTH)}\"", e)
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
                Log.w(
                    TAG,
                    "audio_query failed: HTTP ${response.code} for speakerId=$speakerId, " +
                        "text=\"${text.take(LOGGED_TEXT_LENGTH)}\": ${response.body.string().take(300)}",
                )
                return null
            }
            return response.body.string()
        }
    }

    // [text] is only carried through for the failure log below — the engine itself, unaware of
    // where synthesis actually fails (audio_query vs. synthesis), so this is what tells us which
    // one crashed on which content, e.g. the Japanese-tokenizer encoding bug in AivisSpeech Engine
    // (style_bert_vits2's BERT feature extraction) that motivated this logging in the first place.
    private fun requestSynthesis(baseUrl: String, audioQueryJson: String, speakerId: Int, text: String): ByteArray? {
        val url = "${baseUrl.trimEnd('/')}/synthesis".toHttpUrl().newBuilder()
            .addQueryParameter("speaker", speakerId.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .post(audioQueryJson.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "synthesis failed: HTTP ${response.code} for speakerId=$speakerId, " +
                        "text=\"${text.take(LOGGED_TEXT_LENGTH)}\": ${response.body.string().take(300)}",
                )
                return null
            }
            return response.body.bytes()
        }
    }

    companion object {
        private const val TAG = "VoicevoxCompatibleClient"

        // Generous relative to the 300-char cap used elsewhere in this file for response bodies —
        // pinpointing which content triggers a server-side failure needs enough of the actual
        // synthesized text to spot the culprit, even well into a longer assistant reply.
        private const val LOGGED_TEXT_LENGTH = 500

        // VOICEVOX Engine's own documented default local port (AivisSpeech Engine defaults to
        // :10101 instead) — just a starting point prefilled for a newly created profile; the user
        // is expected to point it at whichever engine/port they actually run.
        const val DEFAULT_BASE_URL = "http://127.0.0.1:50021"
    }
}
