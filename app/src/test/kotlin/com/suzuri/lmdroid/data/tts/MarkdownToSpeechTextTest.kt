package com.suzuri.lmdroid.data.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownToSpeechTextTest {

    @Test
    fun `strips bold and italic markers but keeps the text`() {
        assertEquals("this is bold", markdownToSpeechText("this is **bold**"))
        assertEquals("this is bold", markdownToSpeechText("this is __bold__"))
        assertEquals("this is italic", markdownToSpeechText("this is *italic*"))
        assertEquals("this is bold and italic", markdownToSpeechText("this is ***bold and italic***"))
        assertEquals("bold and italic together", markdownToSpeechText("**bold** and *italic* together"))
    }

    @Test
    fun `strips strikethrough but keeps the text`() {
        assertEquals("this is wrong", markdownToSpeechText("this is ~~wrong~~"))
    }

    @Test
    fun `does not mangle snake_case identifiers with underscore emphasis stripping`() {
        assertEquals("my_variable_name is undefined", markdownToSpeechText("my_variable_name is undefined"))
        assertEquals("use snake_case_names here", markdownToSpeechText("use snake_case_names here"))
    }

    @Test
    fun `still strips genuine underscore emphasis at word boundaries`() {
        assertEquals("use emphasis here", markdownToSpeechText("use _emphasis_ here"))
    }

    @Test
    fun `strips inline code spans but keeps the content`() {
        assertEquals("run npm install first", markdownToSpeechText("run `npm install` first"))
    }

    @Test
    fun `strips fenced code block markers but keeps the code content`() {
        val markdown = """
            Here's the fix:
            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        assertEquals("Here's the fix:\nval x = 1", markdownToSpeechText(markdown))
    }

    @Test
    fun `strips heading markers but keeps the heading text`() {
        assertEquals("Section Title", markdownToSpeechText("## Section Title"))
        assertEquals("Top Level", markdownToSpeechText("# Top Level"))
    }

    @Test
    fun `strips blockquote markers but keeps the quoted text`() {
        assertEquals("this is quoted", markdownToSpeechText("> this is quoted"))
    }

    @Test
    fun `removes horizontal rules entirely`() {
        assertEquals("before\n\nafter", markdownToSpeechText("before\n\n---\n\nafter"))
    }

    @Test
    fun `strips list markers but keeps the item text`() {
        val markdown = """
            - first item
            - second item
            1. step one
            2. step two
        """.trimIndent()

        assertEquals("first item\nsecond item\nstep one\nstep two", markdownToSpeechText(markdown))
    }

    @Test
    fun `speaks link text and drops the url`() {
        assertEquals("see the docs for details", markdownToSpeechText("see [the docs](https://example.com/docs) for details"))
    }

    @Test
    fun `speaks image alt text and drops the url`() {
        assertEquals("a diagram", markdownToSpeechText("![a diagram](https://example.com/diagram.png)"))
    }

    @Test
    fun `unescapes backslash-escaped punctuation`() {
        assertEquals("2 * 3 = 6", markdownToSpeechText("2 \\* 3 = 6"))
    }

    @Test
    fun `plain text with no markdown is unchanged`() {
        assertEquals("今日は良い天気ですね。", markdownToSpeechText("今日は良い天気ですね。"))
    }
}
