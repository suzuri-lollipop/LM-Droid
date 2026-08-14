package com.suzuri.lmdroid.data.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperTextFilterTest {

    @Test
    fun `keeps ordinary speech unchanged`() {
        assertEquals("今日はいい天気ですね", cleanWhisperTranscript("今日はいい天気ですね"))
        assertEquals("what time is it", cleanWhisperTranscript("what time is it"))
        assertEquals("こんにちは。今日はいい天気ですね。", cleanWhisperTranscript("こんにちは。今日はいい天気ですね。"))
    }

    @Test
    fun `drops bracketed sound tags entirely`() {
        assertEquals("", cleanWhisperTranscript("(音楽)"))
        assertEquals("", cleanWhisperTranscript("（音楽）"))
        assertEquals("", cleanWhisperTranscript("[Music]"))
        assertEquals("", cleanWhisperTranscript("[music]"))
        assertEquals("", cleanWhisperTranscript("【音楽】"))
        assertEquals("", cleanWhisperTranscript("(upbeat music)"))
        assertEquals("", cleanWhisperTranscript("[沈黙]"))
    }

    @Test
    fun `strips sound tags but keeps real speech around them`() {
        assertEquals("こんにちは", cleanWhisperTranscript("(音楽)こんにちは"))
        assertEquals("こんにちは", cleanWhisperTranscript("[Music] こんにちは"))
        assertEquals("こんにちはさようなら", cleanWhisperTranscript("こんにちは(笑い)さようなら"))
    }

    @Test
    fun `drops known hallucination phrases regardless of punctuation and case`() {
        assertEquals("", cleanWhisperTranscript("Thank you for watching!"))
        assertEquals("", cleanWhisperTranscript("thank you for watching."))
        assertEquals("", cleanWhisperTranscript("Thank you."))
        assertEquals("", cleanWhisperTranscript("Please subscribe"))
        assertEquals("", cleanWhisperTranscript("ご視聴ありがとうございました。"))
        assertEquals("", cleanWhisperTranscript("ご視聴ありがとうございます"))
        assertEquals("", cleanWhisperTranscript("チャンネル登録よろしくお願いします！"))
        assertEquals("", cleanWhisperTranscript("字幕"))
    }

    @Test
    fun `keeps real speech that merely contains a hallucination phrase`() {
        assertEquals(
            "さっきの動画はご視聴ありがとうございましたと言っていた",
            cleanWhisperTranscript("さっきの動画はご視聴ありがとうございましたと言っていた"),
        )
    }

    @Test
    fun `drops repetition loops`() {
        assertEquals("", cleanWhisperTranscript("ありがとうありがとうありがとう"))
        assertEquals("", cleanWhisperTranscript("ありがとうありがとうありがとうあり"))
        assertEquals("", cleanWhisperTranscript("the day of the day of the day of"))
        assertEquals("", cleanWhisperTranscript("aaaaaaaa"))
    }

    @Test
    fun `keeps short genuine repetitions`() {
        assertEquals("はいはいはい", cleanWhisperTranscript("はいはいはい"))
        assertEquals("もうもう", cleanWhisperTranscript("もうもう"))
    }
}
