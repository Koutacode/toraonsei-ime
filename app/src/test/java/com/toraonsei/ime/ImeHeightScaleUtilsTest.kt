package com.toraonsei.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeHeightScaleUtilsTest {

    @Test
    fun `progressの下限は最小スケールへクランプされる`() {
        assertEquals(0.78f, ImeHeightScaleUtils.scaleFromProgress(-10), 0.0001f)
        assertEquals(0.78f, ImeHeightScaleUtils.scaleFromProgress(0), 0.0001f)
    }

    @Test
    fun `progressの上限は最大スケールへクランプされる`() {
        assertEquals(1.25f, ImeHeightScaleUtils.scaleFromProgress(47), 0.0001f)
        assertEquals(1.25f, ImeHeightScaleUtils.scaleFromProgress(99), 0.0001f)
    }

    @Test
    fun `scaleからprogressへ変換して往復しても大きくズレない`() {
        val samples = listOf(0.78f, 0.90f, 1.0f, 1.10f, 1.25f)
        samples.forEach { scale ->
            val progress = ImeHeightScaleUtils.progressFromScale(scale)
            val restored = ImeHeightScaleUtils.scaleFromProgress(progress)
            assertEquals(scale, restored, 0.011f)
        }
    }

    @Test
    fun `100パーセントは期待するprogressへ変換される`() {
        assertEquals(22, ImeHeightScaleUtils.progressFromScale(1.0f))
    }
}
