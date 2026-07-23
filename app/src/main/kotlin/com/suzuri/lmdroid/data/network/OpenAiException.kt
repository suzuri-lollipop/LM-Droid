package com.suzuri.lmdroid.data.network

/**
 * User-facing error hierarchy for OpenAI API calls. [userMessage] is safe to show directly in the UI.
 */
sealed class OpenAiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    object InvalidApiKey : OpenAiException("APIキーが無効です。設定画面でキーを確認してください。")

    data class RateLimited(val retryAfterSeconds: Int?) : OpenAiException(
        if (retryAfterSeconds != null) {
            "リクエストが多すぎます。${retryAfterSeconds}秒後にもう一度お試しください。"
        } else {
            "リクエストが多すぎます。しばらくしてからもう一度お試しください。"
        },
    )

    data class BadRequest(val serverMessage: String) : OpenAiException(serverMessage)

    data class ServerError(val code: Int) : OpenAiException("サーバーエラーが発生しました (HTTP $code)。しばらくしてからもう一度お試しください。")

    data class NetworkError(val originalCause: Throwable) :
        OpenAiException("ネットワークエラーが発生しました。通信状況を確認してください。", originalCause)

    data class Unknown(val originalCause: Throwable?) :
        OpenAiException("不明なエラーが発生しました。", originalCause)
}
