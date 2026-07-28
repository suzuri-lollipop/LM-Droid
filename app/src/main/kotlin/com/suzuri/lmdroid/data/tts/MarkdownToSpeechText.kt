package com.suzuri.lmdroid.data.tts

/**
 * Flattens Markdown formatting into plain spoken text — applied by [AssistSpeechPlayer.speak]
 * before handing text to either TTS backend, so neither one narrates syntax like "asterisk
 * asterisk" or "shaap shaap" for **bold** or `## headers`. This is a pragmatic regex pass, not a
 * full CommonMark parser: good enough for the prose/code-snippet mix a chat assistant actually
 * replies with, not a general-purpose Markdown-to-text converter.
 */
fun markdownToSpeechText(markdown: String): String {
    var text = markdown

    // Fenced code blocks: drop the ``` fence lines (and any language tag) but keep the code's own
    // text, so a reply that's mostly a code snippet still gets read instead of going silent. The
    // trailing \n? is consumed along with the line itself so removing a fence doesn't leave a
    // blank line behind (a real blank line elsewhere still survives as its own separate \n\n).
    text = text.replace(Regex("(?m)^ {0,3}```[^\n]*\n?"), "")

    // Inline code spans: `code` -> code.
    text = text.replace(Regex("`([^`]+)`"), "$1")

    // Images before links (same [...] shape, just prefixed with !) — spoken as the alt text.
    text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    // Links: spoken as the link text; the URL itself is never useful read aloud.
    text = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")

    // Bold/italic, longest marker first so a combined ***text*** doesn't leave a stray pair of
    // asterisks behind once the outer marker is stripped.
    text = text.replace(Regex("(\\*\\*\\*|___)(.+?)\\1"), "$2")
    text = text.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    // Single-asterisk/underscore emphasis, but only when the marker sits at a word boundary —
    // otherwise identifiers like snake_case_name or a*b would get mangled into "snakecasename"/"ab".
    text = text.replace(Regex("(?<!\\w)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"), "$1")
    text = text.replace(Regex("(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)"), "$1")

    text = text.replace(Regex("~~(.+?)~~"), "$1")

    // Headers/blockquotes: only the line-leading marker is noise — the heading/quote text itself
    // should still be read.
    text = text.replace(Regex("(?m)^ {0,3}#{1,6}\\s+"), "")
    text = text.replace(Regex("(?m)^ {0,3}>\\s?"), "")

    // Horizontal rules: a whole line made of nothing but one repeated rule character.
    text = text.replace(Regex("(?m)^ {0,3}([-*_])\\s*(?:\\1\\s*){2,}$"), "")

    // List markers: the bullet/number isn't meaningful spoken aloud — the line break between
    // items already reads as a natural pause.
    text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")

    // Backslash-escaped punctuation, e.g. \* or \# meant to be read literally.
    text = text.replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~])"), "$1")

    // The stripping above can leave behind runs of blank lines (e.g. a removed horizontal rule) —
    // collapsed so pauses between paragraphs don't stretch out unnaturally.
    text = text.replace(Regex("\n{3,}"), "\n\n")

    return text.trim()
}
