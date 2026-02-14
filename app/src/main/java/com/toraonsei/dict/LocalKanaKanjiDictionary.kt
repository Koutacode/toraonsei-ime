package com.toraonsei.dict

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalKanaKanjiDictionary(private val context: Context) {

    private val dictionaryRef = AtomicReference<Map<String, List<String>>>(emptyMap())

    suspend fun loadIfNeeded() = withContext(Dispatchers.IO) {
        if (dictionaryRef.get().isNotEmpty()) {
            return@withContext
        }

        val loaded = linkedMapOf<String, List<String>>()
        context.assets.open(ASSET_FILE_NAME).bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab >= line.length - 1) return@forEach

                val reading = line.substring(0, tab).trim()
                if (reading.isBlank()) return@forEach

                val candidates = line.substring(tab + 1)
                    .split('|')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(10)
                if (candidates.isNotEmpty()) {
                    loaded[reading] = candidates
                }
            }
        }
        dictionaryRef.set(loaded)
    }

    fun candidatesFor(reading: String, limit: Int = 10): List<String> {
        if (reading.isBlank()) return emptyList()
        val map = dictionaryRef.get()
        if (map.isEmpty()) return emptyList()
        return map[reading].orEmpty().take(limit)
    }

    fun isLoaded(): Boolean = dictionaryRef.get().isNotEmpty()

    companion object {
        const val ASSET_FILE_NAME = "kana_kanji_base.tsv"
    }
}

