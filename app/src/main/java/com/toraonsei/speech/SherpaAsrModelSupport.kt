package com.toraonsei.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

object SherpaAsrModelSupport {

    private fun preferredThreadCount(): Int {
        return Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }

    enum class LanguageHint {
        JAPANESE,
        MULTILINGUAL,
        NON_JAPANESE,
        UNKNOWN
    }

    data class ResolvedModel(
        val name: String,
        val rootDir: File,
        val modelConfig: OfflineModelConfig,
        val requiredFiles: List<File>,
        val languageHint: LanguageHint
    ) {
        val bytes: Long get() = requiredFiles.sumOf { it.length() }
        val isJapaneseCapable: Boolean
            get() = languageHint == LanguageHint.JAPANESE ||
                languageHint == LanguageHint.MULTILINGUAL
    }

    data class ModelStatus(
        val fast: ResolvedModel?,
        val accurate: ResolvedModel?,
        val baseDir: File
    ) {
        val hasAny: Boolean get() = fast != null || accurate != null
        val hasSecondPass: Boolean
            get() = fast != null &&
                accurate != null &&
                fast.rootDir.absolutePath != accurate.rootDir.absolutePath
        val isJapaneseCapable: Boolean
            get() = listOfNotNull(fast, accurate).any { it.isJapaneseCapable }
    }

    fun detect(context: Context): ModelStatus {
        val base = baseDir(context)
        val fast = resolveFirstAvailable(
            base,
            listOf("fast", "first_pass", "online", "streaming", "asr_fast")
        )
        val accurate = resolveFirstAvailable(
            base,
            listOf("accurate", "second_pass", "offline", "asr_accurate", "final")
        )
        val fallback = resolveModelFromDir(base)
        return ModelStatus(
            fast = fast ?: fallback,
            accurate = accurate ?: fallback,
            baseDir = base
        )
    }

    fun baseDir(context: Context): File {
        return File(context.filesDir, "sherpa_asr")
    }

    fun ensureBaseDir(context: Context): File {
        return baseDir(context).also { if (!it.exists()) it.mkdirs() }
    }

    private fun resolveFirstAvailable(base: File, names: List<String>): ResolvedModel? {
        names.forEach { name ->
            val dir = File(base, name)
            resolveModelFromDir(dir)?.let { return it }
        }
        return null
    }

    private fun resolveModelFromDir(dir: File): ResolvedModel? {
        if (!dir.exists() || !dir.isDirectory) return null

        val transducer = resolveTransducer(dir)
        if (transducer != null) return transducer

        val paraformer = resolveSingleOnnx(dir)
        if (paraformer != null) return paraformer

        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { child ->
                resolveModelFromDir(child)?.let { return it }
            }

        return null
    }

    private fun resolveTransducer(dir: File): ResolvedModel? {
        val encoder = findLatestMatch(dir, Regex("^encoder.*\\.onnx$", RegexOption.IGNORE_CASE))
        val decoder = findLatestMatch(dir, Regex("^decoder.*\\.onnx$", RegexOption.IGNORE_CASE))
        val joiner = findLatestMatch(dir, Regex("^joiner.*\\.onnx$", RegexOption.IGNORE_CASE))
        val tokens = findLatestMatch(dir, Regex("^tokens\\.txt$", RegexOption.IGNORE_CASE))
        if (encoder == null || decoder == null || joiner == null || tokens == null) return null

        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokens.absolutePath,
            modelType = "transducer",
            numThreads = preferredThreadCount()
        )
        return ResolvedModel(
            name = "transducer",
            rootDir = dir,
            modelConfig = modelConfig,
            requiredFiles = listOf(encoder, decoder, joiner, tokens),
            languageHint = refineLanguageHint(
                base = detectLanguageHint(
                    dir = dir,
                    names = listOf(encoder.name, decoder.name, joiner.name, tokens.name)
                ),
                tokensFile = tokens
            )
        )
    }

    private fun resolveSingleOnnx(dir: File): ResolvedModel? {
        val model = findLatestMatch(dir, Regex("^model.*\\.onnx$", RegexOption.IGNORE_CASE))
        val tokens = findLatestMatch(dir, Regex("^tokens\\.txt$", RegexOption.IGNORE_CASE))
        if (model == null || tokens == null) return null

        val contextName = "${dir.name.lowercase()} ${model.name.lowercase()}"
        val senseLike = contextName.contains("sense")
        val nemoLike = !senseLike &&
            (contextName.contains("nemo") || contextName.contains("parakeet"))

        val modelConfig = when {
            senseLike -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = "ja"
                ),
                tokens = tokens.absolutePath,
                numThreads = preferredThreadCount()
            )

            nemoLike -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = model.absolutePath
                ),
                tokens = tokens.absolutePath,
                numThreads = preferredThreadCount()
            )

            else -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = model.absolutePath
                ),
                tokens = tokens.absolutePath,
                modelType = "paraformer",
                numThreads = preferredThreadCount()
            )
        }

        return ResolvedModel(
            name = when {
                senseLike -> "sensevoice"
                nemoLike -> "nemo_ctc"
                else -> "paraformer"
            },
            rootDir = dir,
            modelConfig = modelConfig,
            requiredFiles = listOf(model, tokens),
            languageHint = if (senseLike) {
                LanguageHint.MULTILINGUAL
            } else {
                refineLanguageHint(
                    base = detectLanguageHint(
                        dir = dir,
                        names = listOf(model.name, tokens.name)
                    ),
                    tokensFile = tokens
                )
            }
        )
    }

    private fun refineLanguageHint(base: LanguageHint, tokensFile: File): LanguageHint {
        if (base != LanguageHint.UNKNOWN) return base
        return detectLanguageHintFromTokens(tokensFile)
    }

    private fun detectLanguageHintFromTokens(tokensFile: File): LanguageHint {
        if (!tokensFile.exists() || !tokensFile.isFile) return LanguageHint.UNKNOWN
        return runCatching {
            var scanned = 0
            var kanaChars = 0
            tokensFile.bufferedReader().useLines { lines ->
                lines.take(1800).forEach { line ->
                    if (line.isBlank()) return@forEach
                    scanned += 1
                    line.forEach { ch ->
                        if (ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヺ') {
                            kanaChars += 1
                        }
                    }
                }
            }
            when {
                scanned <= 0 -> LanguageHint.UNKNOWN
                kanaChars >= 10 -> LanguageHint.JAPANESE
                else -> LanguageHint.UNKNOWN
            }
        }.getOrDefault(LanguageHint.UNKNOWN)
    }

    private fun findLatestMatch(dir: File, pattern: Regex): File? {
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && pattern.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
    }

    private fun detectLanguageHint(dir: File, names: List<String>): LanguageHint {
        val joined = buildString {
            append(dir.absolutePath.lowercase())
            append(' ')
            names.forEach { append(it.lowercase()); append(' ') }
        }

        val hasJapaneseMarker = japaneseMarkers.any { joined.contains(it) }
        val hasNonJapaneseMarker = nonJapaneseMarkers.any { joined.contains(it) }
        return when {
            hasJapaneseMarker && hasNonJapaneseMarker -> LanguageHint.MULTILINGUAL
            hasJapaneseMarker -> LanguageHint.JAPANESE
            hasNonJapaneseMarker -> LanguageHint.NON_JAPANESE
            else -> LanguageHint.UNKNOWN
        }
    }

    private val japaneseMarkers = listOf(
        "japanese",
        "reazonspeech",
        "sense-voice",
        "sensevoice",
        "ja-jp",
        "-ja-",
        "_ja_",
        "ja_",
        "_ja",
        "ja."
    )

    private val nonJapaneseMarkers = listOf(
        "english",
        "-en-",
        "_en_",
        "russian",
        "-ru-",
        "_ru_",
        "korean",
        "-ko-",
        "_ko_",
        "french",
        "-fr-",
        "_fr_",
        "german",
        "-de-",
        "_de_",
        "spanish",
        "-es-",
        "_es_",
        "chinese",
        "-zh-",
        "_zh_",
        "thai",
        "-th-",
        "_th_",
        "vietnam",
        "-vi-",
        "_vi_"
    )
}
