package com.toraonsei.ime

import kotlin.math.roundToInt

internal object VoiceSessionTextUtils {

    private val voiceJoinBoundaryChars = setOf(
        '.', ',', ';', ':', '!', '?', '。', '、', '！', '？', '・', '…', '〜', 'ー'
    )

    fun normalizeSegment(text: String): String {
        return text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun merge(current: String, incoming: String): String {
        if (incoming.isBlank()) return current
        if (current.isBlank()) return incoming
        if (incoming == current) return current
        if (current.endsWith(incoming) || current.contains(incoming)) return current
        if (incoming.contains(current)) return incoming

        val maxOverlap = minOf(current.length, incoming.length)
        var overlap = 0
        for (length in maxOverlap downTo 2) {
            if (current.regionMatches(current.length - length, incoming, 0, length)) {
                overlap = length
                break
            }
        }
        if (overlap > 0) {
            return current + incoming.substring(overlap)
        }

        val last = current.lastOrNull()
        val first = incoming.firstOrNull()
        return if (shouldInsertSpace(last = last, first = first)) {
            "$current $incoming"
        } else {
            current + incoming
        }
    }

    fun shouldInsertSpace(last: Char?, first: Char?): Boolean {
        if (last == null || first == null) return false
        if (last.isWhitespace() || first.isWhitespace()) return false
        if (last in voiceJoinBoundaryChars) return false
        if (first in voiceJoinBoundaryChars) return false
        return true
    }
}

internal object ImeHeightScaleUtils {
    const val minScale = 0.78f
    const val maxScale = 1.25f
    const val step = 0.01f
    const val maxProgress = 47
    val presets = listOf(0.90f, 1.0f, 1.10f)

    fun scaleFromProgress(progress: Int): Float {
        val clamped = progress.coerceIn(0, maxProgress)
        val raw = minScale + clamped * step
        return raw.coerceIn(minScale, maxScale)
    }

    fun progressFromScale(scale: Float): Int {
        val clamped = scale.coerceIn(minScale, maxScale)
        return ((clamped - minScale) / step)
            .roundToInt()
            .coerceIn(0, maxProgress)
    }
}
