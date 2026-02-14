package com.toraonsei.engine

import android.content.Context
import androidx.core.net.toUri
import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalLlmSupport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid

class LocalLlmInferenceEngine(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    data class Request(
        val input: String,
        val beforeCursor: String,
        val afterCursor: String,
        val appHistory: String,
        val sceneMode: UsageSceneMode,
        val strength: FormatStrength
    )

    data class Response(
        val text: String,
        val modelUsed: Boolean,
        val reason: String
    )

    private val appContext = context.applicationContext
    private val lock = Any()
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

        synchronized(lock) {
            tokenBuffer.setLength(0)
        }

        val prompt = buildPrompt(request)
        val completionResult = runCatching {
            llamaAndroid?.launchCompletion(
                id = localContextId,
                params = mapOf(
                    "prompt" to prompt,
                    "emit_partial_completion" to true,
                    "temperature" to 0.15,
                    "top_p" to 0.9,
                    "top_k" to 40,
                    "penalty_repeat" to 1.03,
                    "n_predict" to maxPredictTokens(request.strength),
                    "stop" to listOf("</s>", "\n\n##", "\n\n---")
                )
            )
        }.getOrNull()

        val rawText = (completionResult?.get("text") as? String).orEmpty()
        val fallbackByTokens = synchronized(lock) { tokenBuffer.toString() }
        val merged = if (rawText.isNotBlank()) rawText else fallbackByTokens
        val sanitized = sanitizeModelOutput(
            source = input,
            candidate = merged
        )

        if (sanitized.isBlank()) {
            return@withContext Response(
                text = "",
                modelUsed = false,
                reason = "empty_or_rejected_output"
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

    private fun buildPrompt(request: Request): String {
        val sceneInstruction = when (request.sceneMode) {
            UsageSceneMode.WORK -> "業務連絡として簡潔で丁寧な日本語に整える"
            UsageSceneMode.MESSAGE -> "LINE/SNS向けに自然で読みやすい日本語に整える"
        }
        val strengthInstruction = when (request.strength) {
            FormatStrength.LIGHT -> "弱め: 誤変換と句読点のみ最小修正"
            FormatStrength.NORMAL -> "標準: 文脈に沿って自然な表記へ調整"
            FormatStrength.STRONG -> "強め: 語順や句読点も含め読みやすく再構成"
        }

        return """
            あなたは日本語IMEの文章整形エンジンです。
            次の制約を守って、入力文を整形してください。
            制約:
            - 新しい事実や推測を追加しない
            - 固有名詞を勝手に作らない
            - 原文の意味を維持する
            - 可能なら文脈に合う漢字へ補正する
            - 句読点、疑問符、感嘆符を必要最小限で補う
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

    private fun maxPredictTokens(strength: FormatStrength): Int {
        return when (strength) {
            FormatStrength.LIGHT -> 96
            FormatStrength.NORMAL -> 144
            FormatStrength.STRONG -> 200
        }
    }

    private fun sanitizeModelOutput(source: String, candidate: String): String {
        var out = candidate
            .replace("\r\n", "\n")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .trim()

        out = out
            .replace(Regex("^(整形後|出力|結果|result)[:：]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '「', '」')

        if (out.isBlank()) return ""
        if (source.length >= 10 && out.length > source.length * 2 + 28) return ""

        val sourceJapanese = japaneseRatio(source)
        val outJapanese = japaneseRatio(out)
        if (sourceJapanese >= 0.45f && outJapanese < 0.35f) return ""

        val overlap = charOverlapRatio(source, out)
        if (source.length >= 8 && overlap < 0.24f) return ""

        return out
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
}
