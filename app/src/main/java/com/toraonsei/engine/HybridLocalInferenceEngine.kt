package com.toraonsei.engine

import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalFormatter

class HybridLocalInferenceEngine(
    private val formatter: LocalFormatter
) {

    enum class Trigger {
        VOICE_INPUT,
        MANUAL_CONVERT
    }

    data class Request(
        val input: String,
        val beforeCursor: String,
        val afterCursor: String,
        val appHistory: String,
        val dictionaryWords: Set<String>,
        val sceneMode: UsageSceneMode,
        val trigger: Trigger,
        val strength: FormatStrength
    )

    fun infer(request: Request): String {
        val source = request.input.trim()
        if (source.isBlank()) return ""

        val baseStrength = resolveStrength(request.trigger, request.sceneMode, request.strength)
        val contextFormatted = formatter.formatWithContext(
            input = source,
            beforeCursor = request.beforeCursor,
            afterCursor = request.afterCursor,
            appHistory = request.appHistory,
            dictionaryWords = request.dictionaryWords,
            strength = baseStrength
        )
        if (contextFormatted.isBlank()) return source

        return when (request.sceneMode) {
            UsageSceneMode.WORK -> applyWorkStyle(contextFormatted, request.trigger)
            UsageSceneMode.MESSAGE -> applyMessageStyle(contextFormatted, request.trigger)
        }
    }

    private fun resolveStrength(
        trigger: Trigger,
        sceneMode: UsageSceneMode,
        userStrength: FormatStrength
    ): FormatStrength {
        return when (trigger) {
            Trigger.MANUAL_CONVERT -> userStrength
            Trigger.VOICE_INPUT -> when (sceneMode) {
                UsageSceneMode.WORK -> FormatStrength.NORMAL
                UsageSceneMode.MESSAGE -> FormatStrength.LIGHT
            }
        }
    }

    private fun applyWorkStyle(input: String, trigger: Trigger): String {
        var text = normalizeWhitespace(input)
        workReplacementRules.forEach { (from, to) ->
            text = text.replace(from, to)
        }
        text = normalizePunctuation(text)
        if (trigger == Trigger.MANUAL_CONVERT) {
            text = enforceWorkSentenceEnding(text)
        } else {
            text = enforceQuestionAndExclamation(text)
        }
        return text.trim()
    }

    private fun applyMessageStyle(input: String, trigger: Trigger): String {
        var text = normalizeWhitespace(input)
        messageReplacementRules.forEach { (from, to) ->
            text = text.replace(from, to)
        }

        text = if (trigger == Trigger.MANUAL_CONVERT) {
            formatter.toCasualMessage(text)
        } else {
            enforceQuestionAndExclamation(text)
        }
        text = normalizeWhitespace(text)
        text = text.replace(Regex("。+"), "。")
        text = text.replace(Regex("！+"), "！")
        text = text.replace(Regex("？+"), "？")

        // メッセージ用途では文末の「。」を軽くし、読みやすさを優先。
        return text
            .replace(Regex("。(?=\\s|$)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun enforceWorkSentenceEnding(input: String): String {
        val units = input
            .split(Regex("(?<=[。！？!?])|\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (units.isEmpty()) return input

        return units.joinToString(" ") { unit ->
            val trimmed = unit.trim()
            when {
                trimmed.endsWith("。") || trimmed.endsWith("！") || trimmed.endsWith("？") -> trimmed
                formatter.isQuestionLike(trimmed) -> "$trimmed？"
                emphasisLikeRegex.containsMatchIn(trimmed) -> "$trimmed！"
                else -> "$trimmed。"
            }
        }.trim()
    }

    private fun enforceQuestionAndExclamation(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.endsWith("？") || trimmed.endsWith("！") || trimmed.endsWith("?") || trimmed.endsWith("!")) {
            return trimmed
        }
        return when {
            formatter.isQuestionLike(trimmed) -> "$trimmed？"
            emphasisLikeRegex.containsMatchIn(trimmed) -> "$trimmed！"
            else -> trimmed
        }
    }

    private fun normalizeWhitespace(input: String): String {
        return input
            .replace(Regex("[\\t\\u3000]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizePunctuation(input: String): String {
        return input
            .replace(Regex("\\s+([。！？])"), "$1")
            .replace(Regex("([。！？]){2,}"), "$1")
            .trim()
    }

    companion object {
        private val questionLikeRegex =
            Regex("(どう|いつ|どこ|何|なに|ですか|ますか|でしょうか|だろうか|かな|かね|だっけ|よね|のか|のかな|ないの|たいの|るの|うの|くの|ぐの|すの|つの|ぬの|ぶの|むの|\\?)")
        private val emphasisLikeRegex =
            Regex("(ありがとう|了解|助かる|お願いします|よろしく|了解しました|承知しました|びっくり|最高|すごい)")

        private val workReplacementRules = listOf(
            "了解" to "了解しました",
            "わかった" to "承知しました",
            "わかりました" to "承知しました",
            "よろしく" to "よろしくお願いします",
            "お願いしますね" to "お願いします"
        )

        private val messageReplacementRules = listOf(
            "承知しました" to "了解",
            "了解しました" to "了解",
            "よろしくお願いします" to "よろしく",
            "ありがとうございました" to "ありがとう",
            "すみませんでした" to "ごめん"
        )
    }
}
