package com.toraonsei.dict

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalKanaKanjiDictionary(private val context: Context) {

    private val dictionaryRef = AtomicReference<Map<String, List<String>>>(emptyMap())
    private val loadedSignatureRef = AtomicReference("")

    suspend fun loadIfNeeded(force: Boolean = false) = withContext(Dispatchers.IO) {
        val dynamicFile = dynamicDictionaryFile().takeIf { it.exists() && it.length() > 0L }
        val packageUpdatedAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

        val signature = buildString {
            append("asset:")
            append(ASSET_FILE_NAME)
            append(':')
            append(packageUpdatedAt)
            if (dynamicFile != null) {
                append("|dynamic:")
                append(dynamicFile.length())
                append(':')
                append(dynamicFile.lastModified())
            }
        }
        if (!force && loadedSignatureRef.get() == signature && dictionaryRef.get().isNotEmpty()) {
            return@withContext
        }

        val loaded = linkedMapOf<String, MutableList<String>>()
        readTsvIntoMap(
            stream = context.assets.open(ASSET_FILE_NAME),
            target = loaded,
            prioritizeIncoming = false
        )
        if (dynamicFile != null) {
            readTsvIntoMap(
                stream = dynamicFile.inputStream(),
                target = loaded,
                prioritizeIncoming = true
            )
        }

        dictionaryRef.set(loaded.mapValues { (_, value) -> value.toList() })
        loadedSignatureRef.set(signature)
    }

    suspend fun reloadFromDisk() {
        loadIfNeeded(force = true)
    }

    fun candidatesFor(reading: String, limit: Int = 10): List<String> {
        if (reading.isBlank()) return emptyList()
        val map = dictionaryRef.get()
        if (map.isEmpty()) return emptyList()
        val normalized = normalizeReadingKana(reading)
        return map[normalized].orEmpty().take(limit)
    }

    fun isLoaded(): Boolean = dictionaryRef.get().isNotEmpty()

    fun hasDynamicDictionary(): Boolean {
        val file = dynamicDictionaryFile()
        return file.exists() && file.length() > 0L
    }

    fun dynamicDictionaryFile(): File {
        return File(context.filesDir, DYNAMIC_FILE_NAME)
    }

    private fun readTsvIntoMap(
        stream: InputStream,
        target: LinkedHashMap<String, MutableList<String>>,
        prioritizeIncoming: Boolean
    ) {
        stream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) return@forEach

                val reading = normalizeReadingKana(line.substring(0, tab).trim())
                if (reading.isBlank()) return@forEach

                val candidates = line.substring(tab + 1)
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(maxCandidatesPerReading)
                if (candidates.isEmpty()) return@forEach

                val existing = target[reading]
                if (existing == null) {
                    target[reading] = candidates.toMutableList()
                    return@forEach
                }

                val merged = if (prioritizeIncoming) {
                    (candidates + existing).distinct()
                } else {
                    (existing + candidates).distinct()
                }
                existing.clear()
                existing.addAll(merged.take(maxCandidatesPerReading))
            }
        }
    }

    private fun normalizeReadingKana(input: String): String {
        if (input.isBlank()) return ""
        val out = StringBuilder(input.length)
        input.forEach { ch ->
            when {
                ch in 'ァ'..'ヶ' -> out.append((ch.code - 0x60).toChar())
                ch == 'ヴ' -> out.append('ゔ')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    companion object {
        const val ASSET_FILE_NAME = "kana_kanji_base.tsv"
        const val DYNAMIC_FILE_NAME = "kana_kanji_dynamic.tsv"
        private const val maxCandidatesPerReading = 16
    }
}
