package com.toraonsei.suggest

import com.toraonsei.model.DictionaryEntry
import com.toraonsei.model.RecentPhrase
import kotlin.math.max

class SuggestionEngine {

    fun generate(
        beforeCursor: String,
        afterCursor: String,
        appHistory: String,
        dictionary: List<DictionaryEntry>,
        recentPhrases: List<RecentPhrase>
    ): List<String> {
        val context = buildString {
            append(beforeCursor)
            append(' ')
            append(appHistory.takeLast(120))
            append(' ')
            append(afterCursor.take(30))
        }

        val token = extractTailToken(beforeCursor.ifBlank { appHistory })
        val candidates = linkedMapOf<String, Int>()

        dictionary.forEach { entry ->
            var score = 1000 + entry.priority * 15
            if (token.isNotBlank()) {
                if (entry.word.contains(token)) score += 220
                if (entry.readingKana.contains(token)) score += 250
            }
            if (beforeCursor.endsWith(entry.readingKana.takeLast(max(1, entry.readingKana.length.coerceAtMost(3))))) {
                score += 120
            }
            upsertScore(candidates, entry.word, score)
        }

        recentPhrases.forEach { phrase ->
            var score = 350 + phrase.frequency * 18
            val agePenalty = ((System.currentTimeMillis() - phrase.lastUsedAt) / 60_000L).toInt().coerceAtMost(500)
            score -= agePenalty
            if (token.isNotBlank() && phrase.text.contains(token)) score += 90
            if (context.contains(phrase.text.take(2))) score += 30
            upsertScore(candidates, phrase.text, score)
        }

        addRuleSuggestions(candidates, context)

        val defaults = listOf(
            "了解",
            "今向かってる",
            "あとで連絡する",
            "ありがとう",
            "大丈夫"
        )
        defaults.forEachIndexed { index, phrase ->
            upsertScore(candidates, phrase, 100 - index * 5)
        }

        return candidates
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.isNotBlank() }
            .filterNot { blockedSuggestionRegex.matches(it.trim()) }
            .distinct()
            .take(8)
    }

    private fun addRuleSuggestions(candidates: MutableMap<String, Int>, context: String) {
        val questionLike = context.contains("?") || questionRegex.containsMatchIn(context)
        if (questionLike) {
            upsertScore(candidates, "どう？", 260)
            upsertScore(candidates, "いつ？", 240)
            upsertScore(candidates, "どこ？", 230)
        }

        if (context.contains("ありがとう") || context.contains("助か")) {
            upsertScore(candidates, "助かる！", 215)
            upsertScore(candidates, "ありがとう！", 205)
        }

        if (context.contains("了解") || context.contains("ok", ignoreCase = true)) {
            upsertScore(candidates, "了解！", 220)
            upsertScore(candidates, "OK", 210)
        }
    }

    private fun upsertScore(map: MutableMap<String, Int>, text: String, score: Int) {
        if (text.isBlank()) return
        val current = map[text]
        if (current == null || score > current) {
            map[text] = score
        }
    }

    private fun extractTailToken(text: String): String {
        val match = tokenRegex.find(text.takeLast(24)) ?: return ""
        return match.value
    }

    private companion object {
        val tokenRegex = Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}A-Za-z0-9]{1,12}$")
        val questionRegex = Regex("(どう|いつ|どこ|何|なに|ですか|ますか|かな)")
        val blockedSuggestionRegex = Regex(
            "^(録音中.*|待機中.*|認識中.*|音声入力.*エラー.*|音声を認識できませんでした)$"
        )
    }
}
