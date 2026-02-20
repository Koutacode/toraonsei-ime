package com.toraonsei.format

enum class FormatStrength(val configValue: String) {
    LIGHT("light"),
    NORMAL("normal"),
    STRONG("strong");

    companion object {
        fun fromConfig(value: String): FormatStrength {
            return values().firstOrNull { it.configValue == value } ?: NORMAL
        }
    }
}

enum class EnglishStyle(val configValue: String) {
    NATURAL("natural"),
    CASUAL("casual"),
    FORMAL("formal");

    companion object {
        fun fromConfig(value: String): EnglishStyle {
            return values().firstOrNull { it.configValue == value } ?: NATURAL
        }
    }
}

class LocalFormatter {

    fun formatWithContext(
        input: String,
        beforeCursor: String,
        afterCursor: String,
        appHistory: String,
        dictionaryWords: Set<String>,
        strength: FormatStrength = FormatStrength.NORMAL
    ): String {
        val cleanedInput = normalize(removeFillers(input))
        if (cleanedInput.isBlank()) return ""

        val context = buildContext(beforeCursor, afterCursor, appHistory)
        val corrected = applyContextCorrections(cleanedInput, context)
        val politeContext = politeRegex.containsMatchIn(context) || politeRegex.containsMatchIn(corrected)

        var rebuilt = when (strength) {
            FormatStrength.LIGHT -> corrected
            FormatStrength.NORMAL,
            FormatStrength.STRONG -> rebuildForContext(corrected, politeContext).ifBlank { corrected }
        }

        rebuilt = when (strength) {
            FormatStrength.LIGHT -> normalizeSentenceMarks(rebuilt)
            FormatStrength.NORMAL -> normalizeSentenceMarks(rebuilt)
            FormatStrength.STRONG -> strengthenSentenceMarks(rebuilt, politeContext)
        }

        rebuilt = enforceDomainTerms(
            source = corrected,
            candidate = rebuilt,
            dictionaryWords = dictionaryWords
        )

        // 末尾の句読点衝突を回避
        if (afterCursor.startsWith("。") || afterCursor.startsWith("、") || afterCursor.startsWith("！") || afterCursor.startsWith("？")) {
            rebuilt = rebuilt.trimEnd('。', '、', '！', '？')
        }

        return rebuilt
    }

    fun toBulletPoints(input: String, dictionaryWords: Set<String>): String {
        val cleaned = normalize(removeFillers(input))
        if (cleaned.isBlank()) return ""

        val segments = splitForRebuild(cleaned)
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
                val units = splitForRebuild(block).ifEmpty { listOf(block) }
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

    fun toEnglishMessage(
        input: String,
        beforeCursor: String = "",
        afterCursor: String = "",
        appHistory: String = "",
        style: EnglishStyle = EnglishStyle.NATURAL
    ): String {
        val cleanedInput = normalize(removeFillers(input))
        if (cleanedInput.isBlank()) return ""

        val context = buildContext(beforeCursor, afterCursor, appHistory)
        val corrected = applyContextCorrections(cleanedInput, context)

        return try {
            val blocks = splitTopicBlocks(corrected).ifEmpty { listOf(corrected) }
            val translatedBlocks = blocks.mapNotNull { block ->
                val units = splitForRebuild(block).ifEmpty { listOf(block) }
                val translatedUnits = units.mapNotNull { unit ->
                    val translated = translateUnitToEnglish(unit)
                    translated.takeIf { it.isNotBlank() }
                }
                if (translatedUnits.isEmpty()) null else translatedUnits.joinToString(" ")
            }

            val output = normalizeEnglish(translatedBlocks.joinToString("\n"))
            val base = if (output.isBlank()) fallbackEnglish(corrected) else output
            applyEnglishStyle(base, style)
        } catch (_: Exception) {
            applyEnglishStyle(fallbackEnglish(corrected), style)
        }
    }

    private fun rebuildForContext(text: String, politeContext: Boolean): String {
        val clauses = splitForRebuild(text)
        if (clauses.isEmpty()) return text

        val deduped = mutableListOf<String>()
        clauses.forEach { clause ->
            val normalizedClause = normalizeClause(clause)
            if (normalizedClause.isBlank()) return@forEach
            val duplicated = deduped.any { existing ->
                isNearDuplicate(existing, normalizedClause)
            }
            if (!duplicated) {
                deduped += normalizedClause
            }
        }

        if (deduped.isEmpty()) return text

        val rebuilt = deduped.map { clause ->
            val ending = decideRebuildEnding(clause, politeContext)
            clause.trimEnd('。', '、', '！', '？', '!', '?') + ending
        }

        return rebuilt
            .joinToString(" ")
            .replace(Regex("\\s+([。！？])"), "$1")
            .replace(Regex("([。！？]){2,}"), "$1")
            .trim()
    }

    private fun decideRebuildEnding(text: String, politeContext: Boolean): String {
        if (text.endsWith("?") || text.endsWith("？")) return ""
        if (isQuestionLike(text)) return "？"
        if (emphasisRegex.containsMatchIn(text)) return "！"
        if (requestRegex.containsMatchIn(text)) return if (politeContext) "。" else ""
        if (politeContext) return "。"
        return if (text.length >= 14) "。" else ""
    }

    private fun splitForRebuild(text: String): List<String> {
        val marked = text
            .replace(topicShiftRegex, "\n$1")
            .replace(rebuildBoundaryRegex, "\n")

        val firstPass = marked
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (firstPass.size > 1) return firstPass

        return firstPass
            .flatMap { unit ->
                unit.split('、')
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeClause(text: String): String {
        var out = normalize(removeFillers(text))
        explicitPunctuationRules.forEach { (regex, replacement) ->
            out = out.replace(regex, replacement)
        }
        out = out.replace(leadingConnectorRegex, "")
        out = out.replace(trailingRedundantRegex, "")
        out = out.trim()
        return out
    }

    private fun isNearDuplicate(a: String, b: String): Boolean {
        val na = normalizeForDedup(a)
        val nb = normalizeForDedup(b)
        if (na.isBlank() || nb.isBlank()) return false
        if (na == nb) return true
        if (na.length >= 6 && nb.contains(na)) return true
        if (nb.length >= 6 && na.contains(nb)) return true
        return false
    }

    private fun enforceDomainTerms(
        source: String,
        candidate: String,
        dictionaryWords: Set<String>
    ): String {
        if (candidate.isBlank()) return source
        val mustKeep = dictionaryWords
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 && source.contains(it) }
            .take(24)
            .toList()
        if (mustKeep.isEmpty()) return candidate

        val missing = mustKeep.count { !candidate.contains(it) }
        if (missing == 0) return candidate

        return if (candidate.length < (source.length * 0.7f).toInt()) {
            source
        } else {
            candidate
        }
    }

    private fun buildContext(beforeCursor: String, afterCursor: String, appHistory: String): String {
        return buildString {
            append(beforeCursor.takeLast(200))
            append(' ')
            append(appHistory.takeLast(260))
            append(' ')
            append(afterCursor.take(120))
        }
    }

    private fun fallbackCasual(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun fallbackEnglish(text: String): String {
        val rough = text
            .replace("、", ", ")
            .replace("。", ". ")
            .replace("？", "? ")
            .replace("！", "! ")
        return normalizeEnglish(rough)
    }

    private fun normalizeSentenceMarks(text: String): String {
        return text
            .replace(Regex("\\s+([。！？])"), "$1")
            .replace(Regex("([。！？]){2,}"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun strengthenSentenceMarks(text: String, politeContext: Boolean): String {
        val units = splitForRebuild(text).ifEmpty { listOf(text) }
        val rebuilt = units.mapNotNull { unit ->
            val trimmed = normalizeClause(unit).trimEnd('。', '！', '？', '.', '!', '?')
            if (trimmed.isBlank()) {
                null
            } else {
                trimmed + decideRebuildEnding(trimmed, politeContext)
            }
        }
        if (rebuilt.isEmpty()) return normalizeSentenceMarks(text)
        return normalizeSentenceMarks(rebuilt.joinToString(" "))
    }

    private fun applyEnglishStyle(text: String, style: EnglishStyle): String {
        if (text.isBlank()) return text
        var out = text
        when (style) {
            EnglishStyle.NATURAL -> {
                // no-op
            }

            EnglishStyle.CASUAL -> {
                casualContractions.forEach { (from, to) ->
                    out = out.replace(from, to, ignoreCase = true)
                }
                casualPhraseRules.forEach { (from, to) ->
                    out = out.replace(from, to, ignoreCase = true)
                }
            }

            EnglishStyle.FORMAL -> {
                formalExpansionRules.forEach { (from, to) ->
                    out = out.replace(from, to, ignoreCase = true)
                }
                formalPhraseRules.forEach { (from, to) ->
                    out = out.replace(from, to, ignoreCase = true)
                }
            }
        }
        return normalizeEnglish(out)
    }

    private fun translateUnitToEnglish(text: String): String {
        var out = normalize(text)
        if (out.isBlank()) return ""

        out = applyTranslations(out, sortedPhraseTranslations)
        out = applyRegexTranslations(out)
        out = applyTranslations(out, sortedWordTranslations)
        out = applyTranslations(out, sortedFunctionTranslations)

        out = out
            .replace("、", ", ")
            .replace("。", ". ")
            .replace("？", "? ")
            .replace("！", "! ")
            .replace(Regex("(です|ます|でした|でしたら|だよ|だね|ですね|ですよ)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 既に英語記号がない疑問文に対して補助的に ? を付与
        if (isQuestionLike(text) && !out.endsWith("?")) {
            out = "$out?"
        }

        return normalizeEnglish(out)
    }

    private fun applyRegexTranslations(text: String): String {
        var out = text
        regexTranslations.forEach { (pattern, replacement) ->
            out = out.replace(pattern, replacement)
        }
        return out
    }

    private fun applyTranslations(text: String, rules: List<Pair<String, String>>): String {
        var out = text
        rules.forEach { (jp, en) ->
            out = out.replace(jp, en)
        }
        return out
    }

    private fun applyContextCorrections(text: String, context: String): String {
        var out = text
        contextCorrections.forEach { rule ->
            val enabled = rule.contextHints.isEmpty() ||
                rule.contextHints.any { hint ->
                    hint.isNotBlank() && (context.contains(hint) || out.contains(hint))
                }
            if (enabled && out.contains(rule.wrong)) {
                out = out.replace(rule.wrong, rule.corrected)
            }
        }
        out = applyHomophoneDisambiguation(out, context)
        return out
    }

    private fun applyHomophoneDisambiguation(text: String, context: String): String {
        if (text.isBlank()) return text
        var out = text

        // 音声入力・変換品質の文脈では「制度」より「精度」を優先する。
        val qualityContext = contextQualityRegex.containsMatchIn(context) ||
            contextQualityRegex.containsMatchIn(out) ||
            seidoQualityPhraseRegex.containsMatchIn(out)
        if (qualityContext) {
            out = out.replace(performanceSeidoRegex, "精度")
        }

        homophonePhraseCorrections.forEach { (pattern, replacement) ->
            out = out.replace(pattern, replacement)
        }

        val mergedContext = "$context $out"
        homophoneCorrections.forEach { rule ->
            if (!out.contains(rule.wrong)) return@forEach
            if (rule.contextHints.isNotEmpty() && !containsAnyHint(mergedContext, rule.contextHints)) {
                return@forEach
            }
            if (rule.forbiddenHints.isNotEmpty() && containsAnyHint(mergedContext, rule.forbiddenHints)) {
                return@forEach
            }
            out = out.replace(rule.wrong, rule.corrected)
        }

        return out
    }

    private fun containsAnyHint(text: String, hints: List<String>): Boolean {
        if (text.isBlank()) return false
        return hints.any { hint ->
            hint.isNotBlank() && text.contains(hint)
        }
    }

    private fun normalizeEnglish(text: String): String {
        var out = text
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("([,.!?])([A-Za-z])"), "$1 $2")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .trim()

        if (out.isEmpty()) return out
        out = out.replace(" .", ".").replace(" ,", ",")

        val lines = out.split('\n').map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@map ""
            val first = trimmed.first()
            if (first in 'a'..'z') {
                first.uppercaseChar() + trimmed.substring(1)
            } else {
                trimmed
            }
        }

        return lines.joinToString("\n").trim()
    }

    private fun decideEnding(text: String): String {
        if (text.endsWith("?") || text.endsWith("？") || text.endsWith("!") || text.endsWith("！")) {
            return ""
        }
        if (isQuestionLike(text)) {
            return "?"
        }
        if (emphasisRegex.containsMatchIn(text)) {
            return "!"
        }
        return ""
    }

    fun isQuestionLike(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim().trimEnd('。', '！', '？', '.', '!', '?')
        if (trimmed.isBlank()) return false
        if (questionRegex.containsMatchIn(trimmed)) return true
        if (questionTailRegex.containsMatchIn(trimmed)) return true
        return false
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

    private data class ContextCorrection(
        val wrong: String,
        val corrected: String,
        val contextHints: List<String> = emptyList()
    )

    private data class HomophoneCorrection(
        val wrong: String,
        val corrected: String,
        val contextHints: List<String> = emptyList(),
        val forbiddenHints: List<String> = emptyList()
    )

    private companion object {
        val fillers = listOf("えー", "えっと", "あの", "そのー", "まあ", "なんか", "うーん", "ええと", "えーと", "あー", "あーと", "まー")
        val explicitPunctuationRules = listOf(
            Regex("はてな$") to "？",
            Regex("ハテナ$") to "？",
            Regex("びっくり$") to "！",
            Regex("ビックリ$") to "！",
            Regex("びっくりまーく$") to "！",
            Regex("クエスチョン$") to "？"
        )
        val topicShiftRegex = Regex("(ところで|ちなみに|それと|あと|話変わるけど)")
        val rebuildBoundaryRegex = Regex("[。！？!?]|(それで|そのあと|ちなみに|ところで|だから|なので|ただし|でも)")
        val leadingConnectorRegex = Regex("^(それで|そのあと|ちなみに|ところで|だから|なので|あと|でも|えっと|あの)+")
        val trailingRedundantRegex = Regex("(ですけど|なんだけど|みたいな感じ|って感じ)$")
        val numberOrTimeRegex = Regex("([0-9０-９]+|[0-9０-９]+時|[0-9０-９]+分)")
        val requestRegex = Regex("(して|してほしい|確認|大丈夫|どう|いつ|どこ|何|\\?)")
        val constraintRegex = Regex("(無理|できない|不可|厳しい)")
        val questionRegex = Regex("(どう|いつ|どこ|何|なに|ですか|ますか|かな|\\?)")
        val questionTailRegex = Regex("(ですか|ますか|でしょうか|だろうか|かな|かね|だっけ|よね|のか|のかな|ないの|たいの|るの|うの|くの|ぐの|すの|つの|ぬの|ぶの|むの|せんか|ませんか|じゃない|じゃないか)$")
        val emphasisRegex = Regex("(ありがとう|了解|助かる|お願いします|よろしく|すごい|最高|びっくり|嬉しい|うれしい|感謝)")
        val politeRegex = Regex("(です|ます|ください|お願|失礼|でしょう|ませ)")
        val contextQualityRegex = Regex("(音声入力|音声認識|認識精度|変換精度|誤変換|認識率|変換率|精度|入力精度|誤字|漢字変換|文脈|変換候補|キーボード|IME|システム|開発)")
        val performanceSeidoRegex = Regex("(制度)(?=(で|が|は|を|に|も|の|って|とか|だと|なら|くらい|ほど|高|低|上|下|改善|向上|落|悪|良))")
        val seidoQualityPhraseRegex = Regex("(かなりの制度で|制度ででき|制度とか漢字|制度(が|は|を|に|も|って)(高|低|上|下|改善|向上|悪|良|気になる|問題|不安))")
        val homophonePhraseCorrections = listOf(
            Regex("かなりの制度で") to "かなりの精度で",
            Regex("制度とか漢字") to "精度とか漢字",
            Regex("音声入力の制度") to "音声入力の精度",
            Regex("変換制度") to "変換精度",
            Regex("認識制度") to "認識精度"
        )

        val contextCorrections = listOf(
            ContextCorrection(
                wrong = "返還",
                corrected = "変換",
                contextHints = listOf("入力", "キーボード", "IME", "文面", "候補", "漢字", "音声")
            ),
            ContextCorrection(
                wrong = "閉める",
                corrected = "閉じる",
                contextHints = listOf("画面", "キーボード", "IME", "入力")
            ),
            ContextCorrection(
                wrong = "開ける",
                corrected = "開く",
                contextHints = listOf("画面", "キーボード", "IME", "入力")
            ),
            ContextCorrection(
                wrong = "こんにちわ",
                corrected = "こんにちは"
            ),
            ContextCorrection(
                wrong = "こんばんわ",
                corrected = "こんばんは"
            )
        )
        val homophoneCorrections = listOf(
            HomophoneCorrection(
                wrong = "制度",
                corrected = "精度",
                contextHints = listOf("音声", "認識", "変換", "誤変換", "入力", "IME", "キーボード", "文脈", "候補", "漢字"),
                forbiddenHints = listOf("社会制度", "法制度", "教育制度", "税制度", "保険制度")
            ),
            HomophoneCorrection(
                wrong = "仕様",
                corrected = "使用",
                contextHints = listOf("使う", "利用", "運用", "実際", "テスト", "実行", "使用"),
                forbiddenHints = listOf("仕様書", "仕様変更", "仕様確認", "仕様です", "仕様に")
            ),
            HomophoneCorrection(
                wrong = "使用",
                corrected = "仕様",
                contextHints = listOf("設計", "要件", "機能", "画面", "挙動", "ルール", "UI", "実装", "仕様"),
                forbiddenHints = listOf("使用中", "使用する", "使用します", "使用した", "使用して", "使用料")
            ),
            HomophoneCorrection(
                wrong = "意外",
                corrected = "以外",
                contextHints = listOf("除く", "だけ", "しか", "ほか", "その他", "全部", "それ"),
                forbiddenHints = listOf("意外と", "意外に", "意外な", "意外だった")
            ),
            HomophoneCorrection(
                wrong = "以外",
                corrected = "意外",
                contextHints = listOf("驚", "まさか", "思ったより", "案外", "意外"),
                forbiddenHints = listOf("以外は", "それ以外", "以外に", "以外で", "以外の", "以外も")
            ),
            HomophoneCorrection(
                wrong = "合う",
                corrected = "会う",
                contextHints = listOf("待ち合わせ", "集合", "現地", "駅", "今日", "明日", "今夜", "あとで", "会う"),
                forbiddenHints = listOf("都合", "相性", "サイズ", "噛み", "辻褄", "似合う", "割合")
            ),
            HomophoneCorrection(
                wrong = "会う",
                corrected = "合う",
                contextHints = listOf("都合", "相性", "サイズ", "噛み", "辻褄", "割合", "文脈", "意味", "似合"),
                forbiddenHints = listOf("待ち合わせ", "集合", "現地", "駅", "再会")
            ),
            HomophoneCorrection(
                wrong = "帰る",
                corrected = "変える",
                contextHints = listOf("設定", "変更", "修正", "直す", "表記", "漢字", "文面", "変換", "スイッチ", "モード"),
                forbiddenHints = listOf("帰宅", "家", "自宅", "帰り", "先に帰る")
            ),
            HomophoneCorrection(
                wrong = "変える",
                corrected = "帰る",
                contextHints = listOf("帰宅", "家", "自宅", "帰り", "終わったら", "今から", "定時", "退社"),
                forbiddenHints = listOf("設定", "変更", "修正", "変換", "置き換")
            ),
            HomophoneCorrection(
                wrong = "飼う",
                corrected = "買う",
                contextHints = listOf("ローソン", "コンビニ", "セブン", "ファミマ", "タバコ", "購入", "買い物", "値段", "円", "レジ", "スーパー", "メルカリ", "Amazon")
            ),
            HomophoneCorrection(
                wrong = "買う",
                corrected = "飼う",
                contextHints = listOf("犬", "猫", "ペット", "餌", "散歩", "里親", "飼い主", "飼育", "ハムスター", "ウサギ")
            ),
            HomophoneCorrection(
                wrong = "替える",
                corrected = "変える",
                contextHints = listOf("設定", "内容", "表記", "文字", "漢字", "変換", "修正")
            ),
            HomophoneCorrection(
                wrong = "変える",
                corrected = "替える",
                contextHints = listOf("交換", "予備", "バッテリー", "タイヤ", "オイル", "部品", "入れ替え")
            ),
            HomophoneCorrection(
                wrong = "再会",
                corrected = "再開",
                contextHints = listOf("録音", "入力", "処理", "更新", "作業", "続き", "停止", "再開")
            ),
            HomophoneCorrection(
                wrong = "再開",
                corrected = "再会",
                contextHints = listOf("久しぶり", "友達", "会え", "会う", "再会")
            ),
            HomophoneCorrection(
                wrong = "後悔",
                corrected = "公開",
                contextHints = listOf("リリース", "配信", "公開", "アップロード", "GitHub", "Notion", "ページ")
            ),
            HomophoneCorrection(
                wrong = "公開",
                corrected = "後悔",
                contextHints = listOf("反省", "ミス", "つらい", "しなければ", "後悔")
            ),
            HomophoneCorrection(
                wrong = "解答",
                corrected = "回答",
                contextHints = listOf("質問", "返事", "返信", "問い合わせ", "アンケート")
            ),
            HomophoneCorrection(
                wrong = "回答",
                corrected = "解答",
                contextHints = listOf("問題", "試験", "テスト", "クイズ", "設問", "正解")
            ),
            HomophoneCorrection(
                wrong = "移行",
                corrected = "以降",
                contextHints = listOf("これから", "今後", "以後", "からは", "以降"),
                forbiddenHints = listOf("データ移行", "移行作業", "移行先", "移行元", "システム移行")
            ),
            HomophoneCorrection(
                wrong = "以降",
                corrected = "移行",
                contextHints = listOf("データ", "システム", "切替", "移す", "マイグレーション", "移行先", "移行元")
            ),
            HomophoneCorrection(
                wrong = "保障",
                corrected = "保証",
                contextHints = listOf("品質", "動作", "メーカー", "保証書", "初期不良", "サポート")
            ),
            HomophoneCorrection(
                wrong = "保証",
                corrected = "補償",
                contextHints = listOf("事故", "損害", "賠償", "弁償", "補償", "保険")
            ),
            HomophoneCorrection(
                wrong = "保証",
                corrected = "保障",
                contextHints = listOf("社会", "生活", "権利", "安全保障", "最低限")
            ),
            HomophoneCorrection(
                wrong = "対照",
                corrected = "対象",
                contextHints = listOf("ユーザー", "項目", "範囲", "条件", "適用", "対象")
            ),
            HomophoneCorrection(
                wrong = "対象",
                corrected = "対照",
                contextHints = listOf("比較", "対照的", "コントラスト", "照らし合わせ")
            ),
            HomophoneCorrection(
                wrong = "習性",
                corrected = "修正",
                contextHints = listOf("誤字", "バグ", "変更", "直す", "更新", "アップデート", "修正")
            ),
            HomophoneCorrection(
                wrong = "海底",
                corrected = "改定",
                contextHints = listOf("料金", "規約", "ルール", "価格", "改定", "改訂")
            ),
            HomophoneCorrection(
                wrong = "信仰",
                corrected = "進行",
                contextHints = listOf("会議", "作業", "進捗", "手順", "進行")
            ),
            HomophoneCorrection(
                wrong = "気象",
                corrected = "起床",
                contextHints = listOf("朝", "寝坊", "起き", "目覚まし", "起床")
            )
        )

        val regexTranslations = listOf(
            Regex("あなた(のお|の)?名前は何ですか[?？]?") to "what is your name?",
            Regex("お?名前は何ですか[?？]?") to "what is your name?",
            Regex("英語で(話せますか|しゃべれますか|喋れますか)[?？]?") to "can you speak in English?",
            Regex("(.+?)を英語にして(ください|下さい)?[。.!！?？]?") to "please translate $1 into English",
            Regex("私の名前は(.+?)(です|だ)[。.!！?？]?") to "my name is $1",
            Regex("今現在(は)?") to "right now",
            Regex("(.+?)は何ですか[?？]?") to "what is $1?",
            Regex("(.+?)を教えてください[。.!！?？]?") to "please tell me about $1",
            Regex("(.+?)をお願いします") to "please $1",
            Regex("(.+?)してください") to "please $1",
            Regex("(.+?)して下さい") to "please $1",
            Regex("(.+?)してほしい") to "please $1",
            Regex("(.+?)できますか") to "can you $1?",
            Regex("(.+?)ですか") to "is $1?",
            Regex("(.+?)にします") to "set to $1"
        )

        val phraseTranslations = listOf(
            "お疲れさまです" to "thanks for your hard work",
            "お疲れ様です" to "thanks for your hard work",
            "おはようございます" to "good morning",
            "こんにちは" to "hello",
            "こんばんは" to "good evening",
            "英語でしゃべれますか" to "can you speak in English",
            "英語で話せますか" to "can you speak in English",
            "英語で喋れますか" to "can you speak in English",
            "あなたのお名前は何ですか" to "what is your name",
            "あなたの名前は何ですか" to "what is your name",
            "お名前は何ですか" to "what is your name",
            "名前は何ですか" to "what is your name",
            "今日の天気予報は何ですか" to "what is today's weather forecast",
            "天気予報は何ですか" to "what is the weather forecast",
            "天気予報を教えてください" to "please tell me the weather forecast",
            "教えてください" to "please tell me",
            "お願いします" to "please",
            "よろしくお願いします" to "thank you in advance",
            "ありがとうございます" to "thank you",
            "ありがとう" to "thanks",
            "すみませんでした" to "I'm sorry",
            "申し訳ないです" to "I'm sorry",
            "了解です" to "got it",
            "了解" to "got it",
            "承知しました" to "understood",
            "承知です" to "understood",
            "大丈夫です" to "it's okay",
            "大丈夫" to "okay",
            "問題ありません" to "no problem",
            "確認お願いします" to "please check",
            "確認して" to "please check",
            "確認しました" to "I checked it",
            "修正しました" to "I fixed it",
            "更新しました" to "I updated it",
            "あとで連絡する" to "I will contact you later",
            "後で返信します" to "I'll reply later",
            "今向かってる" to "I'm on my way",
            "今向かっています" to "I'm on my way",
            "今から向かいます" to "I'm heading there now",
            "あと5分で着きます" to "I'll arrive in 5 minutes",
            "少し遅れます" to "I'll be a little late",
            "遅れます" to "I'll be late",
            "先に始めてください" to "please start first",
            "今電話できますか" to "can you talk now",
            "いま電話できますか" to "can you talk now",
            "今大丈夫ですか" to "is now okay",
            "いま大丈夫ですか" to "is now okay",
            "後で大丈夫ですか" to "is later okay",
            "後ほどお願いします" to "please later",
            "よろしくです" to "thanks in advance",
            "ごめん" to "sorry",
            "すみません" to "sorry",
            "助かります" to "that helps",
            "助かった" to "that helped",
            "文面整形" to "message formatting",
            "変換候補" to "conversion candidates",
            "録音開始" to "start recording",
            "録音停止" to "stop recording",
            "録音中" to "recording",
            "再開します" to "resume",
            "通知を表示" to "show notification"
        )

        val wordTranslations = listOf(
            "今日" to "today",
            "明日" to "tomorrow",
            "昨日" to "yesterday",
            "先週" to "last week",
            "来週" to "next week",
            "時間" to "time",
            "予定" to "schedule",
            "会議" to "meeting",
            "打ち合わせ" to "meeting",
            "資料" to "document",
            "電話" to "call",
            "連絡" to "contact",
            "返信" to "reply",
            "確認" to "check",
            "対応" to "handle",
            "修正" to "fix",
            "更新" to "update",
            "入力" to "input",
            "音声" to "voice",
            "変換" to "convert",
            "文脈" to "context",
            "日本語" to "Japanese",
            "英語" to "English",
            "担当" to "owner",
            "共有" to "share",
            "完了" to "done",
            "未完了" to "pending",
            "急ぎ" to "urgent",
            "至急" to "urgent",
            "開始" to "start",
            "終了" to "finish",
            "開始時間" to "start time",
            "終了時間" to "end time",
            "場所" to "place",
            "会社" to "company",
            "自宅" to "home",
            "名前" to "name",
            "お名前" to "name",
            "何" to "what",
            "天気予報" to "weather forecast",
            "天気" to "weather",
            "録音" to "recording",
            "候補" to "candidate",
            "通知" to "notification",
            "画面" to "screen",
            "キーボード" to "keyboard",
            "今" to "now",
            "あとで" to "later"
        )

        val functionTranslations = listOf(
            "私は" to "I ",
            "僕は" to "I ",
            "俺は" to "I ",
            "あなたは" to "you ",
            "あなた" to "you",
            "これ" to "this",
            "それ" to "that",
            "を" to " ",
            "に" to " ",
            "が" to " ",
            "は" to " ",
            "で" to " ",
            "へ" to " to ",
            "と" to " and ",
            "から" to " from ",
            "まで" to " to ",
            "も" to " also ",
            "ね" to "",
            "よ" to ""
        )

        val sortedPhraseTranslations = phraseTranslations.sortedByDescending { it.first.length }
        val sortedWordTranslations = wordTranslations.sortedByDescending { it.first.length }
        val sortedFunctionTranslations = functionTranslations.sortedByDescending { it.first.length }
        val casualContractions = listOf(
            "I will" to "I'll",
            "I am" to "I'm",
            "do not" to "don't",
            "cannot" to "can't",
            "can not" to "can't",
            "it is" to "it's",
            "that is" to "that's"
        )
        val casualPhraseRules = listOf(
            "Thank you" to "Thanks",
            "thanks for your hard work" to "thanks",
            "I am on my way" to "I'm on my way"
        )
        val formalExpansionRules = listOf(
            "I'm" to "I am",
            "I'll" to "I will",
            "don't" to "do not",
            "can't" to "cannot",
            "it's" to "it is",
            "that's" to "that is"
        )
        val formalPhraseRules = listOf(
            "Thanks" to "Thank you",
            "Got it" to "Understood",
            "got it" to "understood"
        )
    }
}
