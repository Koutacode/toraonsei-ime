package com.toraonsei.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatterEnglishTest {

    private val formatter = LocalFormatter()

    @Test
    fun `名前質問を英訳できる`() {
        val result = formatter.toEnglishMessage(
            input = "こんにちは あなたの名前は何ですか?",
            style = EnglishStyle.NATURAL
        )

        assertTrue(result.lowercase().contains("name"))
        assertFalse(containsJapaneseChars(result))
    }

    @Test
    fun `お名前質問を英訳できる`() {
        val result = formatter.toEnglishMessage(
            input = "お名前は何ですか",
            style = EnglishStyle.NATURAL
        )

        assertTrue(result.lowercase().contains("what is your name"))
        assertFalse(containsJapaneseChars(result))
    }

    @Test
    fun `天気予報質問を英訳できる`() {
        val result = formatter.toEnglishMessage(
            input = "今日の天気予報は何ですか？教えてください",
            style = EnglishStyle.NATURAL
        )

        val lower = result.lowercase()
        assertTrue(lower.contains("weather"))
        assertTrue(lower.contains("today") || lower.contains("today's"))
        assertFalse(containsJapaneseChars(result))
    }

    @Test
    fun `英語で話せるか質問を英訳できる`() {
        val result = formatter.toEnglishMessage(
            input = "英語でしゃべれますか？",
            style = EnglishStyle.NATURAL
        )

        val lower = result.lowercase()
        assertTrue(lower.contains("speak"))
        assertTrue(lower.contains("english"))
        assertFalse(containsJapaneseChars(result))
    }

    @Test
    fun `私の名前文を英訳できる`() {
        val result = formatter.toEnglishMessage(
            input = "私の名前は太郎です",
            style = EnglishStyle.NATURAL
        )

        val lower = result.lowercase()
        assertTrue(lower.contains("my name is"))
    }

    private fun containsJapaneseChars(text: String): Boolean {
        return text.any { ch ->
            ch in 'ぁ'..'ゖ' ||
                ch in 'ァ'..'ヺ' ||
                ch in '一'..'龯' ||
                ch == '々' || ch == '〆' || ch == '〤'
        }
    }
}
