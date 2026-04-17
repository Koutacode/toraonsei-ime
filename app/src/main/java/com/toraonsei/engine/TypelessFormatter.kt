package com.toraonsei.engine

import com.toraonsei.dict.UserDictionaryRepository
import com.toraonsei.format.FormatStrength
import com.toraonsei.model.DictionaryEntry

class TypelessFormatter(
    private val llm: LocalLlmInferenceEngine,
    private val userDictionary: UserDictionaryRepository
) {
    data class Request(
        val rawText: String,
        val sceneMode: UsageSceneMode,
        val appPackageName: String? = null,
        val appLabel: String? = null
    )

    data class Result(
        val formatted: String,
        val usedLlm: Boolean,
        val reason: String
    )

    suspend fun format(request: Request): Result {
        val trimmed = request.rawText.trim()
        if (trimmed.isBlank()) {
            return Result("", false, "empty_input")
        }

        val entries = runCatching { userDictionary.getEntriesOnce() }.getOrDefault(emptyList())
        val preReplaced = applyReadingToWord(trimmed, entries)
        val appHint = buildAppContextHint(request, entries)

        val llmRequest = LocalLlmInferenceEngine.Request(
            input = preReplaced,
            beforeCursor = "",
            afterCursor = "",
            appHistory = appHint,
            sceneMode = request.sceneMode,
            strength = FormatStrength.NORMAL,
            task = LocalLlmInferenceEngine.Task.REFINE_JAPANESE
        )
        val response = llm.refine(llmRequest)

        val chosen = if (response.modelUsed && response.text.isNotBlank()) {
            response.text
        } else {
            preReplaced
        }

        val postDict = applyReadingToWord(chosen, entries)
        val final = applySceneEnding(postDict, request.sceneMode)
        return Result(final, response.modelUsed, response.reason)
    }

    private fun applyReadingToWord(text: String, entries: List<DictionaryEntry>): String {
        if (entries.isEmpty()) return text
        var out = text
        entries
            .asSequence()
            .filter { it.readingKana.isNotBlank() && it.word.isNotBlank() }
            .sortedByDescending { it.readingKana.length }
            .forEach { entry ->
                if (entry.readingKana != entry.word) {
                    out = out.replace(entry.readingKana, entry.word)
                }
            }
        return out
    }

    private fun buildAppContextHint(
        request: Request,
        entries: List<DictionaryEntry>
    ): String {
        val parts = mutableListOf<String>()
        val appName = request.appLabel?.takeIf { it.isNotBlank() }
            ?: request.appPackageName?.takeIf { it.isNotBlank() }
        if (appName != null) {
            val toneHint = toneHintForPackage(request.appPackageName)
            parts += "対象アプリ: $appName${if (toneHint.isNotBlank()) " ($toneHint)" else ""}"
        }
        if (entries.isNotEmpty()) {
            val preferred = entries
                .asSequence()
                .filter { it.word.isNotBlank() }
                .sortedByDescending { it.priority }
                .take(16)
                .joinToString("、") { "${it.word}(${it.readingKana})" }
            if (preferred.isNotBlank()) {
                parts += "優先表記: $preferred"
            }
        }
        return parts.joinToString(" / ")
    }

    private fun toneHintForPackage(pkg: String?): String {
        if (pkg.isNullOrBlank()) return ""
        return when {
            pkg.contains("line", ignoreCase = true) -> "カジュアル会話"
            pkg.contains("slack", ignoreCase = true) -> "同僚チャット"
            pkg.contains("discord", ignoreCase = true) -> "カジュアル会話"
            pkg.contains("twitter", ignoreCase = true) || pkg.contains("com.x.", ignoreCase = true) -> "短文・口語"
            pkg.contains("gmail", ignoreCase = true) || pkg.contains("mail", ignoreCase = true) -> "フォーマル・メール"
            pkg.contains("outlook", ignoreCase = true) -> "フォーマル・メール"
            pkg.contains("teams", ignoreCase = true) -> "ビジネスチャット"
            pkg.contains("notion", ignoreCase = true) -> "ドキュメント体"
            pkg.contains("docs", ignoreCase = true) -> "ドキュメント体"
            else -> ""
        }
    }

    private fun applySceneEnding(text: String, mode: UsageSceneMode): String {
        val trimmed = text.trimEnd()
        return when (mode) {
            UsageSceneMode.MESSAGE -> {
                var out = trimmed
                while (out.endsWith("。")) {
                    out = out.dropLast(1).trimEnd()
                }
                out
            }
            UsageSceneMode.WORK -> trimmed
        }
    }
}
