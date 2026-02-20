package com.toraonsei.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSessionTextUtilsTest {

    @Test
    fun `segment正規化で空白と改行を圧縮する`() {
        val actual = VoiceSessionTextUtils.normalizeSegment("  今日は\n  天気   です  ")
        assertEquals("今日は 天気 です", actual)
    }

    @Test
    fun `末尾と先頭が重なる場合は重複を除去して連結する`() {
        val actual = VoiceSessionTextUtils.merge(
            current = "今日は天気",
            incoming = "天気です"
        )
        assertEquals("今日は天気です", actual)
    }

    @Test
    fun `incomingが既存文に含まれる場合は既存文を保持する`() {
        val actual = VoiceSessionTextUtils.merge(
            current = "今日は天気です",
            incoming = "天気です"
        )
        assertEquals("今日は天気です", actual)
    }

    @Test
    fun `incomingが既存文を含む場合はincomingを優先する`() {
        val actual = VoiceSessionTextUtils.merge(
            current = "今日は天気",
            incoming = "今日は天気です"
        )
        assertEquals("今日は天気です", actual)
    }

    @Test
    fun `英単語同士はスペースを挿入して連結する`() {
        val actual = VoiceSessionTextUtils.merge(
            current = "hello",
            incoming = "world"
        )
        assertEquals("hello world", actual)
    }

    @Test
    fun `句読点境界ではスペースを挿入しない`() {
        val actual = VoiceSessionTextUtils.merge(
            current = "hello,",
            incoming = "world"
        )
        assertEquals("hello,world", actual)
    }
}
