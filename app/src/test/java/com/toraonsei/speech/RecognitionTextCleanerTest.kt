package com.toraonsei.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionTextCleanerTest {

    private val cleaner = RecognitionTextCleaner()

    @Test
    fun `finalではフィラーを除去する`() {
        val cleaned = cleaner.cleanFinal("えー えっと これから向かいます")
        assertEquals("これから向かいます", cleaned)
    }

    @Test
    fun `finalではノイズ記号を除去する`() {
        val cleaned = cleaner.cleanFinal("お願いします♪♪")
        assertEquals("お願いします", cleaned)
    }

    @Test
    fun `partialでも空白は正規化する`() {
        val cleaned = cleaner.cleanPartial("  これから   向かう  ")
        assertEquals("これから 向かう", cleaned)
    }
}
