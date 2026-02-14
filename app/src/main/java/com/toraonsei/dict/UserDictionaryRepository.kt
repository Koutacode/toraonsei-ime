package com.toraonsei.dict

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toraonsei.model.DictionaryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dictionaryDataStore by preferencesDataStore(name = "user_dictionary")

class UserDictionaryRepository(private val context: Context) {

    private val jsonKey = stringPreferencesKey("entries_json")

    val entriesFlow: Flow<List<DictionaryEntry>> = context.dictionaryDataStore.data.map { prefs ->
        decodeEntries(prefs[jsonKey])
    }

    suspend fun getEntriesOnce(): List<DictionaryEntry> = entriesFlow.first()

    suspend fun upsert(word: String, readingKana: String, priority: Int = 0) {
        val normalizedWord = word.trim()
        val normalizedReading = readingKana.trim()
        if (normalizedWord.isBlank() || normalizedReading.isBlank()) {
            return
        }

        context.dictionaryDataStore.edit { prefs ->
            val mutable = decodeEntries(prefs[jsonKey]).toMutableList()
            val now = System.currentTimeMillis()
            val index = mutable.indexOfFirst { it.word == normalizedWord }
            val newEntry = DictionaryEntry(
                word = normalizedWord,
                readingKana = normalizedReading,
                priority = priority,
                createdAt = if (index >= 0) mutable[index].createdAt else now
            )
            if (index >= 0) {
                mutable[index] = newEntry
            } else {
                mutable += newEntry
            }
            prefs[jsonKey] = encodeEntries(mutable)
        }
    }

    suspend fun upsert(entry: DictionaryEntry) {
        upsert(entry.word, entry.readingKana, entry.priority)
    }

    suspend fun remove(word: String) {
        val normalizedWord = word.trim()
        if (normalizedWord.isBlank()) {
            return
        }

        context.dictionaryDataStore.edit { prefs ->
            val mutable = decodeEntries(prefs[jsonKey]).toMutableList()
            mutable.removeAll { it.word == normalizedWord }
            prefs[jsonKey] = encodeEntries(mutable)
        }
    }

    suspend fun importFromJson(rawJson: String): ImportResult {
        val imported = parseImportPayload(rawJson)

        context.dictionaryDataStore.edit { prefs ->
            val merged = linkedMapOf<String, DictionaryEntry>()
            decodeEntries(prefs[jsonKey]).forEach { existing ->
                merged[existing.word] = existing
            }
            imported.forEach { incoming ->
                merged[incoming.word] = incoming.copy(
                    createdAt = merged[incoming.word]?.createdAt ?: incoming.createdAt
                )
            }
            prefs[jsonKey] = encodeEntries(merged.values.toList())
        }

        return ImportResult(importedCount = imported.size)
    }

    fun exportToJson(entries: List<DictionaryEntry>): String {
        val payload = JSONObject()
        payload.put("version", 1)
        payload.put("entries", JSONArray().apply {
            entries.sortedByDescending { it.priority }.forEach { entry ->
                put(JSONObject().apply {
                    put("word", entry.word)
                    put("readingKana", entry.readingKana)
                    put("priority", entry.priority)
                    put("createdAt", entry.createdAt)
                })
            }
        })
        return payload.toString(2)
    }

    private fun decodeEntries(raw: String?): List<DictionaryEntry> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            parseImportPayload(raw)
        } catch (_: Exception) {
            emptyList()
        }.sortedWith(compareByDescending<DictionaryEntry> { it.priority }.thenBy { it.word })
    }

    private fun encodeEntries(entries: List<DictionaryEntry>): String {
        return exportToJson(entries)
    }

    private fun parseImportPayload(raw: String): List<DictionaryEntry> {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("entries") ?: JSONArray()
            }
            else -> JSONArray()
        }

        val result = mutableListOf<DictionaryEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val word = obj.optString("word").trim()
            val reading = obj.optString("readingKana").trim()
            if (word.isBlank() || reading.isBlank()) {
                continue
            }
            result += DictionaryEntry(
                word = word,
                readingKana = reading,
                priority = obj.optInt("priority", 0),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }

        // 同一wordが複数ある場合は最後に現れたものを有効化
        val merged = linkedMapOf<String, DictionaryEntry>()
        result.forEach { merged[it.word] = it }
        return merged.values.toList()
    }

    data class ImportResult(
        val importedCount: Int
    )
}
