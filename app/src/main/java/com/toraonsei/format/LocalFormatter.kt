package com.toraonsei.format

class LocalFormatter {

    fun formatWithContext(
        input: String,
        beforeCursor: String,
        afterCursor: String,
        appHistory: String,
        dictionaryWords: Set<String>
    ): String {
        val cleaned = normalize(removeFillers(input))
        if (cleaned.isBlank()) return ""

        val context = buildString {
            append(beforeCursor.takeLast(120))
            append(' ')
            append(appHistory.takeLast(180))
            append(' ')
            append(afterCursor.take(60))
        }

        val segments = splitSegments(cleaned).ifEmpty { listOf(cleaned) }
        var merged = segments.joinToString("、") { it.trim() }
        merged = merged.replace(Regex("、+"), "、").trim('、', ' ')

        // 文脈に辞書語が出る場合は、語を崩しにくいよう元文を優先
        val hasDomainTerm = dictionaryWords.any { it.isNotBlank() && context.contains(it) && cleaned.contains(it) }
        if (hasDomainTerm) {
            merged = cleaned
        }

        val politeContext = politeRegex.containsMatchIn(context)
        if (politeContext) {
            merged = toPoliteTone(merged)
        }

        merged = when {
            questionRegex.containsMatchIn(merged) -> merged.trimEnd('。', '！', '？', '!', '?') + "？"
            emphasisRegex.containsMatchIn(merged) -> merged.trimEnd('。', '？', '?') + "！"
            politeContext -> merged.trimEnd('！', '？', '!', '?') + "。"
            else -> merged
        }

        if (afterCursor.startsWith("。") || afterCursor.startsWith("、") || afterCursor.startsWith("！") || afterCursor.startsWith("？")) {
            merged = merged.trimEnd('。', '、', '！', '？')
        }

        return merged
    }

    fun toBulletPoints(input: String, dictionaryWords: Set<String>): String {
        val cleaned = normalize(removeFillers(input))
        if (cleaned.isBlank()) return ""

        val segments = splitSegments(cleaned)
        if (segments.isEmpty()) {
            return "- ${compress(cleaned)}"
        }

        val scored = segments.mapIndexed { index, segment ->
            ScoredSegment(
                index = index,
                text = segment,
                score = scoreSegment(segment, dictionaryWords)
            )
        }

        val chosen = scored
            .sortedWith(compareByDescending<ScoredSegment> { it.score }.thenByDescending { it.text.length })
            .take(10)
            .sortedBy { it.index }
            .map { compress(it.text) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeForDedup(it) }
            .take(10)

        if (chosen.isEmpty()) {
            return "- ${compress(cleaned)}"
        }

        return chosen.joinToString("\n") { "- $it" }
    }

    fun toCasualMessage(input: String): String {
        val cleaned = normalize(removeFillers(input))
        if (cleaned.isBlank()) return ""

        return try {
            val blocks = splitTopicBlocks(cleaned).mapNotNull { block ->
                val units = splitSegments(block).ifEmpty { listOf(block) }
                val formatted = units.mapNotNull { unit ->
                    val trimmed = unit.trim().trimEnd('。', '！', '？', '.', '!', '?')
                    if (trimmed.isBlank()) {
                        null
                    } else {
                        trimmed + decideEnding(trimmed)
                    }
                }
                if (formatted.isEmpty()) null else formatted.joinToString(" ")
            }

            if (blocks.isEmpty()) {
                fallbackCasual(cleaned)
            } else {
                blocks.joinToString("。\n")
            }
        } catch (_: Exception) {
            fallbackCasual(cleaned)
        }
    }

    private fun fallbackCasual(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun toPoliteTone(text: String): String {
        var out = text
        out = out.replace(Regex("だよ$"), "ですよ")
        out = out.replace(Regex("だね$"), "ですね")
        out = out.replace(Regex("だ$"), "です")
        out = out.replace(Regex("する$"), "します")
        return out
    }

    private fun decideEnding(text: String): String {
        if (text.endsWith("?") || text.endsWith("？") || text.endsWith("!" ) || text.endsWith("！")) {
            return ""
        }
        if (questionRegex.containsMatchIn(text)) {
            return "?"
        }
        if (emphasisRegex.containsMatchIn(text)) {
            return "!"
        }
        return ""
    }

    private fun scoreSegment(segment: String, dictionaryWords: Set<String>): Int {
        var score = 1
        if (numberOrTimeRegex.containsMatchIn(segment)) score += 3
        if (requestRegex.containsMatchIn(segment)) score += 3
        if (constraintRegex.containsMatchIn(segment)) score += 2
        if (questionRegex.containsMatchIn(segment)) score += 2
        if (dictionaryWords.any { it.isNotBlank() && segment.contains(it) }) score += 2
        score += (segment.length / 20).coerceAtMost(2)
        return score
    }

    private fun splitTopicBlocks(text: String): List<String> {
        val marked = topicShiftRegex.replace(text) { "\n${it.value}" }
        return marked
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun splitSegments(text: String): List<String> {
        return text
            .replace(topicShiftRegex, "$0\n")
            .split(segmentSplitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun compress(text: String): String {
        var compressed = text
            .replace(Regex("(という感じ|みたいな感じ|って感じ)$"), "")
            .replace(Regex("(ですけど|なんだけど)$"), "")
            .trim()

        if (compressed.length > 40) {
            compressed = compressed.take(40).trimEnd()
        }
        return compressed
    }

    private fun normalizeForDedup(text: String): String {
        return text
            .replace(Regex("[\\s、。！？!?]"), "")
            .trim()
    }

    private fun removeFillers(text: String): String {
        var out = text
        fillers.forEach { filler ->
            out = out.replace(filler, "")
        }
        return out
    }

    private fun normalize(text: String): String {
        return text
            .replace(Regex("[\\t\\u3000]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class ScoredSegment(
        val index: Int,
        val text: String,
        val score: Int
    )

    private companion object {
        val fillers = listOf("えー", "えっと", "あの", "そのー", "まあ", "なんか", "うーん", "ええと")
        val segmentSplitRegex = Regex("[\\n。！？!?]|(で|それで|あと|ちなみに|ところで)")
        val topicShiftRegex = Regex("(ところで|ちなみに|それと|あと|話変わるけど)")
        val numberOrTimeRegex = Regex("([0-9０-９]+|[0-9０-９]+時|[0-9０-９]+分)")
        val requestRegex = Regex("(して|してほしい|確認|大丈夫|どう|いつ|どこ|何|\\?)")
        val constraintRegex = Regex("(無理|できない|不可|厳しい)")
        val questionRegex = Regex("(どう|いつ|どこ|何|なに|ですか|ますか|かな|\\?)")
        val emphasisRegex = Regex("(ありがとう|了解|助かる|お願いします|よろしく)")
        val politeRegex = Regex("(です|ます|ください|お願|失礼|でしょう)")
    }
}
