package com.toraonsei.engine

import android.content.Context
import androidx.core.net.toUri
import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalLlmSupport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid

class LocalLlmInferenceEngine(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    enum class Task {
        REFINE_JAPANESE,
        TRANSLATE_ENGLISH
    }

    data class Request(
        val input: String,
        val beforeCursor: String,
        val afterCursor: String,
        val appHistory: String,
        val sceneMode: UsageSceneMode,
        val strength: FormatStrength,
        val task: Task = Task.REFINE_JAPANESE
    )

    data class Response(
        val text: String,
        val modelUsed: Boolean,
        val reason: String
    )

    private val appContext = context.applicationContext
    private val lock = Any()
    private val completionMutex = Mutex()
    private var llamaAndroid: LlamaAndroid? = null
    private var contextId: Int? = null
    private var loadedModelPath: String = ""
    private val tokenBuffer = StringBuilder()

    suspend fun refine(request: Request): Response = withContext(dispatcher) {
        val input = request.input.trim()
        if (input.isBlank()) {
            return@withContext Response(
                text = "",
                modelUsed = false,
                reason = "empty_input"
            )
        }

        val status = LocalLlmSupport.detect(appContext)
        if (!status.available) {
            return@withContext Response(
                text = "",
                modelUsed = false,
                reason = status.reason
            )
        }

        val localContextId = ensureContextLoaded(status.modelPath) ?: return@withContext Response(
            text = "",
            modelUsed = false,
            reason = "load_failed"
        )

        val prompt = buildPrompt(request)
        val temperature = when (request.task) {
            Task.TRANSLATE_ENGLISH -> 0.08
            Task.REFINE_JAPANESE -> 0.15
        }
        val topP = when (request.task) {
            Task.TRANSLATE_ENGLISH -> 0.82
            Task.REFINE_JAPANESE -> 0.90
        }
        val topK = when (request.task) {
            Task.TRANSLATE_ENGLISH -> 32
            Task.REFINE_JAPANESE -> 40
        }
        val params = mapOf(
            "prompt" to prompt,
            "emit_partial_completion" to true,
            "temperature" to temperature,
            "top_p" to topP,
            "top_k" to topK,
            "penalty_repeat" to 1.03,
            "n_predict" to maxPredictTokens(
                task = request.task,
                strength = request.strength,
                inputChars = input.length
            ),
            "stop" to listOf("</s>", "\n\n##", "\n\n---")
        )
        val completion = completionMutex.withLock {
            synchronized(lock) {
                tokenBuffer.setLength(0)
            }
            launchCompletionWithRetry(
                contextId = localContextId,
                params = params
            )
        }

        val completionResult = completion.payload
        val rawText = (completionResult?.get("text") as? String).orEmpty()
        val fallbackByTokens = synchronized(lock) { tokenBuffer.toString() }
        val merged = if (rawText.isNotBlank()) rawText else fallbackByTokens
        val sanitized = sanitizeModelOutput(
            task = request.task,
            source = input,
            candidate = merged
        )

        if (sanitized.isBlank()) {
            val reason = when {
                completion.reason == "context_busy" -> "context_busy"
                merged.isBlank() && completion.reason != "ok" -> completion.reason
                else -> "empty_or_rejected_output"
            }
            return@withContext Response(
                text = "",
                modelUsed = false,
                reason = reason
            )
        }

        return@withContext Response(
            text = sanitized,
            modelUsed = true,
            reason = "ok"
        )
    }

    fun release() {
        synchronized(lock) {
            val localContextId = contextId
            if (localContextId != null) {
                runCatching { llamaAndroid?.releaseContext(localContextId) }
            }
            contextId = null
            loadedModelPath = ""
            tokenBuffer.setLength(0)
        }
    }

    private fun ensureContextLoaded(modelPath: String): Int? {
        synchronized(lock) {
            if (contextId != null && loadedModelPath == modelPath) {
                return contextId
            }
            release()

            val engine = llamaAndroid ?: LlamaAndroid(appContext.contentResolver).also {
                llamaAndroid = it
            }
            val modelUri = LocalLlmSupport.toModelUri(modelPath)
            val uri = modelUri.toUri()
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val modelFd = pfd.detachFd()
            val config = mapOf<String, Any>(
                "model" to modelUri,
                "model_fd" to modelFd,
                "use_mmap" to false,
                "use_mlock" to false,
                "n_ctx" to 2048,
                "n_threads" to Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            )

            val startResult = runCatching {
                engine.startEngine(config) { token ->
                    synchronized(lock) {
                        tokenBuffer.append(token)
                    }
                }
            }.getOrNull()
            runCatching { pfd.close() }

            val id = (startResult?.get("contextId") as? Number)?.toInt() ?: return null
            contextId = id
            loadedModelPath = modelPath
            return id
        }
    }

    private data class CompletionAttempt(
        val payload: Map<String, Any>?,
        val reason: String
    )

    private suspend fun launchCompletionWithRetry(
        contextId: Int,
        params: Map<String, Any>
    ): CompletionAttempt {
        var lastReason = "completion_failed"

        repeat(maxBusyRetryCount + 1) { attempt ->
            val result = runCatching {
                llamaAndroid?.launchCompletion(
                    id = contextId,
                    params = params
                )
            }

            val payload = result.getOrNull()
            if (payload != null) {
                return CompletionAttempt(
                    payload = payload,
                    reason = "ok"
                )
            }

            val throwable = result.exceptionOrNull()
            val message = throwable?.message.orEmpty()
            val busy = message.contains("Context is busy", ignoreCase = true)
            lastReason = if (busy) "context_busy" else "completion_failed"
            if (!busy || attempt >= maxBusyRetryCount) {
                return CompletionAttempt(
                    payload = null,
                    reason = lastReason
                )
            }
            delay((attempt + 1) * busyRetryDelayMs)
        }

        return CompletionAttempt(
            payload = null,
            reason = lastReason
        )
    }

    private fun buildPrompt(request: Request): String {
        val sceneInstruction = when (request.sceneMode) {
            UsageSceneMode.WORK -> "仕事モード: 敬語・丁寧語を使い、簡潔で明確な文に整える"
            UsageSceneMode.MESSAGE -> "会話モード: 少し砕けた自然な口語に整える"
        }
        val strengthInstruction = when (request.strength) {
            FormatStrength.LIGHT -> "弱め: 誤変換と句読点のみ最小修正"
            FormatStrength.NORMAL -> "標準: 文脈に沿って自然な表記へ調整"
            FormatStrength.STRONG -> "強め: 語順や句読点も含め読みやすく再構成"
        }

        if (request.task == Task.TRANSLATE_ENGLISH) {
            val englishTone = when (request.sceneMode) {
                UsageSceneMode.WORK -> "business English (polite, professional)"
                UsageSceneMode.MESSAGE -> "casual English (friendly, natural)"
            }
            val strictness = when (request.strength) {
                FormatStrength.LIGHT -> "literal (minimal rephrasing)"
                FormatStrength.NORMAL -> "natural (keep meaning, improve readability)"
                FormatStrength.STRONG -> "rewrite for clarity (still faithful)"
            }
            return """
                You are an IME translation engine.
                Translate the text inside <input>...</input> into $englishTone.
                Strictness: $strictness

                Rules:
                - Translate everything; do not leave Japanese words unless they are proper nouns/brands.
                - Do not add new facts or guesses.
                - Do not omit any sentence or request from the input.
                - Do not summarize; keep the original meaning and intent line-by-line.
                - Keep names, numbers, URLs, and emojis as-is.
                - Preserve line breaks (if any) and use natural punctuation (. , ? !).
                - Output ONLY the translated text. No explanations, labels, or quotes.
                - If the input is Japanese, the output must be English.

                Example:
                <input>こんにちは あなたの名前は何ですか？</input>
                Hello, what is your name?

                <input>
                ${request.input.trim()}
                </input>
            """.trimIndent()
        }

        return """
            あなたは日本語IMEの文章整形エンジンです。
            次の制約を守って、入力文を整形してください。
            制約:
            - 新しい事実や推測を追加しない
            - 固有名詞を勝手に作らない
            - 原文の意味を維持する
            - 日本語として不自然な難読漢字・当て字への置換をしない
            - 無関係なカタカナ化をしない（原文がカタカナ語の場合のみ維持）
            - 句読点（、。）、疑問符（？）、感嘆符（！）を自然に補う
            - 音の伸ばしは必要に応じて長音記号「ー」を使う
            - 出力は整形後テキストのみ（解説不要）
            - 日本語で出力する

            利用シーン: $sceneInstruction
            整形強度: $strengthInstruction
            前文脈: ${request.beforeCursor.takeLast(200)}
            後文脈: ${request.afterCursor.take(80)}
            アプリ履歴: ${request.appHistory.takeLast(260)}
            入力文: ${request.input.trim()}
        """.trimIndent()
    }

    private fun maxPredictTokens(
        task: Task,
        strength: FormatStrength,
        inputChars: Int
    ): Int {
        val base = when (task) {
            Task.REFINE_JAPANESE -> when (strength) {
                FormatStrength.LIGHT -> 96
                FormatStrength.NORMAL -> 144
                FormatStrength.STRONG -> 200
            }
            Task.TRANSLATE_ENGLISH -> when (strength) {
                FormatStrength.LIGHT -> 140
                FormatStrength.NORMAL -> 200
                FormatStrength.STRONG -> 260
            }
        }
        val bonus = when (task) {
            Task.TRANSLATE_ENGLISH -> (inputChars / 90).coerceAtMost(90)
            Task.REFINE_JAPANESE -> (inputChars / 60).coerceAtMost(140)
        }
        val maxTokens = when (task) {
            Task.TRANSLATE_ENGLISH -> 360
            Task.REFINE_JAPANESE -> 520
        }
        return (base + bonus).coerceAtMost(maxTokens)
    }

    private fun sanitizeModelOutput(task: Task, source: String, candidate: String): String {
        var out = candidate
            .replace("\r\n", "\n")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .trim()

        out = out
            .replace(Regex("^(整形後|出力|結果|result|translation)[:：]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '「', '」')

        if (out.isBlank()) return ""

        return when (task) {
            Task.REFINE_JAPANESE -> sanitizeJapaneseRefinementOutput(source = source, out = out)
            Task.TRANSLATE_ENGLISH -> sanitizeEnglishTranslationOutput(source = source, out = out)
        }
    }

    private fun sanitizeJapaneseRefinementOutput(source: String, out: String): String {
        var result = out
        if (source.length >= 10 && result.length > source.length * 2 + 28) return ""

        val sourceJapanese = japaneseRatio(source)
        val outJapanese = japaneseRatio(result)
        if (sourceJapanese >= 0.45f && outJapanese < 0.35f) return ""

        val overlap = charOverlapRatio(source, result)
        if (source.length >= 8 && overlap < 0.24f) return ""
        if (isLikelyKatakanaRewrite(source, result)) return ""

        result = normalizeJapaneseMarks(result)
        return result
    }

    private fun sanitizeEnglishTranslationOutput(source: String, out: String): String {
        var result = out
        if (source.length >= 10 && result.length > (source.length * 2.8f + 48f).toInt()) return ""

        val latin = latinRatio(result)
        if (latin < 0.08f) return ""

        // 日本語が残りすぎる場合は翻訳失敗として弾く（固有名詞程度は許容）。
        val sourceJapanese = japaneseRatio(source)
        val outJapanese = japaneseRatio(result)
        if (sourceJapanese >= 0.45f && outJapanese >= 0.22f) return ""

        result = normalizeEnglishMarks(result)
        return result
    }

    private fun latinRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val chars = text.filterNot { it.isWhitespace() }
        if (chars.isBlank()) return 0f
        val latin = chars.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' }
        return latin.toFloat() / chars.length.toFloat()
    }

    private fun japaneseRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val chars = text.filterNot { it.isWhitespace() }
        if (chars.isEmpty()) return 0f
        val jpCount = chars.count { ch ->
            ch in 'ぁ'..'ゖ' ||
                ch in 'ァ'..'ヺ' ||
                ch in '一'..'龯' ||
                ch == '々' || ch == '〆' || ch == '〤'
        }
        return jpCount.toFloat() / chars.length.toFloat()
    }

    private fun charOverlapRatio(source: String, target: String): Float {
        val src = source.filterNot { it.isWhitespace() }
        if (src.isBlank()) return 1f
        if (target.isBlank()) return 0f
        val hit = src.count { ch -> target.contains(ch) }
        return hit.toFloat() / src.length.toFloat()
    }

    private fun isLikelyKatakanaRewrite(source: String, candidate: String): Boolean {
        val src = source.filterNot { it.isWhitespace() }
        val out = candidate.filterNot { it.isWhitespace() }
        if (src.isBlank() || out.isBlank()) return false

        val sourceKatakanaRatio = katakanaRatio(src)
        val candidateKatakanaRatio = katakanaRatio(out)
        if (sourceKatakanaRatio >= 0.26f) return false
        if (candidateKatakanaRatio < 0.46f) return false

        // 元文がカタカナ主体でないのに、出力だけカタカナだらけになる崩れを除外する。
        return out.length >= src.length
    }

    private fun katakanaRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val katakana = text.count { ch -> ch in 'ァ'..'ヺ' || ch == 'ー' }
        return katakana.toFloat() / text.length.toFloat()
    }

    private fun normalizeJapaneseMarks(text: String): String {
        return text
            .replace("?", "？")
            .replace("!", "！")
            .replace(Regex("\\s+([、。！？])"), "$1")
            .replace(Regex("([、。！？]){2,}"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeEnglishMarks(text: String): String {
        val normalized = text
            .replace("\r\n", "\n")
            .replace("？", "?")
            .replace("！", "!")
            .replace("。", ".")
            .replace("、", ",")

        return normalized
            .lines()
            .joinToString("\n") { line ->
                line.trim()
                    .replace(Regex("[\\t ]+"), " ")
                    .replace(Regex("\\s+([,\\.\\?!])"), "$1")
                    .replace(Regex("([,\\.\\?!]){2,}"), "$1")
                    .trim()
            }
            .trim()
    }

    private companion object {
        const val maxBusyRetryCount = 2
        const val busyRetryDelayMs = 110L
    }
}
