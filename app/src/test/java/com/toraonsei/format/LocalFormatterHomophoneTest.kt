package com.toraonsei.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFormatterHomophoneTest {

    private val formatter = LocalFormatter()

    @Test
    fun `精度文脈では制度を精度へ補正する`() {
        val result = formatter.formatWithContext(
            input = "かなりの制度でできてる",
            beforeCursor = "音声入力の変換精度を上げたい",
            afterCursor = "",
            appHistory = "",
            dictionaryWords = emptySet()
        )

        assertTrue(result.contains("精度"))
    }

    @Test
    fun `社会制度の文脈では制度を維持する`() {
        val result = formatter.formatWithContext(
            input = "社会制度を見直したい",
            beforeCursor = "",
            afterCursor = "",
            appHistory = "",
            dictionaryWords = emptySet()
        )

        assertTrue(result.contains("制度"))
        assertFalse(result.contains("社会精度"))
    }

    @Test
    fun `ローソン文脈では飼うを買うへ補正する`() {
        val result = formatter.formatWithContext(
            input = "これからローソンでタバコを飼う",
            beforeCursor = "",
            afterCursor = "",
            appHistory = "",
            dictionaryWords = emptySet()
        )

        assertTrue(result.contains("買う"))
    }
}
