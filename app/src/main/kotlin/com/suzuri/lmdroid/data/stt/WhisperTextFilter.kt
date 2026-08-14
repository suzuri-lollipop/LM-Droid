package com.suzuri.lmdroid.data.stt

/**
 * Cleans Whisper's raw output before it is shown as a transcript or sent as the user's message.
 * On music, noise and silence the model hallucinates instead of staying quiet — non-speech tags
 * like "[Music]"/"(音楽)", subtitle-style stock phrases ("Thank you for watching",
 * "ご視聴ありがとうございました"), and runaway repetition loops ("ありがとうありがとうありがとう…") —
 * and any of those reaching the UI reads as if the user had said them. Returns "" whenever the
 * text is judged to be such junk, so the caller treats the utterance as unrecognized instead.
 */
fun cleanWhisperTranscript(raw: String): String {
    // Non-speech sound tags always come wrapped in brackets. The whole content is dropped
    // (not just the brackets) — "background (音楽) hello" should keep "background hello".
    val withoutSoundTags = SOUND_TAG_PATTERN.replace(raw, "").trim()

    val normalized = normalizeForComparison(withoutSoundTags)
    if (normalized.isEmpty()) return ""
    if (normalized in HALLUCINATION_PHRASES) return ""
    if (isRepetitive(normalized)) return ""
    return withoutSoundTags
}

// Covers ASCII and full-width parentheses plus the bracket styles Whisper's ja/en output uses.
// Both brackets are escaped inside the negated classes: java.util.regex reads an unescaped '['
// in a class as the start of a nested class, which made "[^[\]]" fail to parse.
private val SOUND_TAG_PATTERN = Regex("""\[[^\[\]]*\]|\([^()]*\)|（[^（）]*）|【[^【】]*】""")

/** Lowercased letters/digits only, so phrase matching ignores punctuation and case. */
private fun normalizeForComparison(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/**
 * Stock phrases Whisper produces on non-speech audio. Entries are pre-normalized (see
 * [normalizeForComparison]); only an utterance matching one of these *in its entirety* is
 * dropped, so a genuine sentence merely containing one of them is never harmed.
 */
private val HALLUCINATION_PHRASES = setOf(
    // English
    "thankyouforwatching",
    "thanksforwatching",
    "thankyouforyourtime",
    "thankyou",
    "thanks",
    "pleasesubscribe",
    "likeandsubscribe",
    "subscribe",
    "seeyousoon",
    "seeyounexttime",
    "goodbye",
    "you",
    "the",
    // Japanese
    "ご視聴ありがとうございました",
    "ご視聴ありがとうございます",
    "ご視聴いただきありがとうございました",
    "本日もご視聴ありがとうございました",
    "最後までご視聴ありがとうございました",
    "チャンネル登録よろしくお願いします",
    "チャンネル登録してね",
    "また次の動画でお会いしましょう",
    "また次回もお楽しみに",
    "字幕",
    "字幕提供",
)

/**
 * True when [normalized] is one short unit repeated over and over — the decoder's classic
 * hallucination loop on noise. Requires at least three full repeats (plus an optional trailing
 * partial one, since loops get cut off mid-unit), and a minimum length so genuine short repeats
 * ("はいはいはい") survive.
 */
private fun isRepetitive(normalized: String): Boolean {
    if (normalized.length < 8) return false
    for (unitLen in 1..normalized.length / 3) {
        val unit = normalized.substring(0, unitLen)
        var index = unitLen
        var repeats = 1
        while (index + unitLen <= normalized.length && normalized.startsWith(unit, index)) {
            repeats++
            index += unitLen
        }
        if (repeats >= 3 && (index == normalized.length || unit.startsWith(normalized.substring(index)))) {
            return true
        }
    }
    return false
}
