package com.suzuri.lmdroid.ui.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistViewModelTest {

    @Test
    fun `returns null when there is no sentence-ending character yet`() {
        assertNull(lastSentenceBoundary("これはまだ途中の文章です"))
        assertNull(lastSentenceBoundary(""))
    }

    @Test
    fun `finds the boundary just past a Japanese period`() {
        val text = "こんにちは。"
        assertEquals(text.length, lastSentenceBoundary(text))
    }

    @Test
    fun `recognizes exclamation and question marks as boundaries too`() {
        assertEquals("すごい！".length, lastSentenceBoundary("すごい！"))
        assertEquals("本当ですか？".length, lastSentenceBoundary("本当ですか？"))
    }

    @Test
    fun `recognizes a line break as a boundary too, e g between list items`() {
        val text = "1つ目の項目\n"
        assertEquals(text.length, lastSentenceBoundary(text))
    }

    @Test
    fun `finds the last boundary when multiple sentences arrived in one update`() {
        val text = "最初の文です。次の文です。まだ続きます"

        val boundary = lastSentenceBoundary(text)

        assertEquals("最初の文です。次の文です。".length, boundary)
    }

    @Test
    fun `a trailing partial sentence after the last boundary is not included`() {
        val text = "完成した文。未完成の"

        val boundary = lastSentenceBoundary(text)

        assertEquals("完成した文。".length, boundary)
    }

    @Test
    fun `keeps the spoken progress while the content is still growing`() {
        assertEquals(6, adjustedSpokenIndex(6, "確認しますね。今日は"))
        assertEquals(6, adjustedSpokenIndex(6, "確認しますね。"))
    }

    @Test
    fun `restarts from zero when a tool round discarded the preamble and the content shrank`() {
        assertEquals(0, adjustedSpokenIndex(12, ""))
        assertEquals(0, adjustedSpokenIndex(12, "晴れです。"))
    }
}
