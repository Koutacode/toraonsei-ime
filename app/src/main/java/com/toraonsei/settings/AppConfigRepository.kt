package com.toraonsei.settings

import android.content.Context
import com.toraonsei.engine.UsageSceneMode
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.appConfigDataStore by preferencesDataStore(name = "app_config")

class AppConfigRepository(private val context: Context) {

    private val unlockedKey = booleanPreferencesKey("ime_unlocked")
    private val usageSceneModeKey = stringPreferencesKey("usage_scene_mode")
    private val formatStrengthKey = stringPreferencesKey("format_strength")
    private val englishStyleKey = stringPreferencesKey("english_style")
    private val recognitionNoiseFilterEnabledKey =
        booleanPreferencesKey("recognition_noise_filter_enabled")
    private val asrModelTreeUriKey = stringPreferencesKey("asr_model_tree_uri")
    private val llmModelUriKey = stringPreferencesKey("llm_model_uri")

    val configFlow: Flow<AppConfig> = context.appConfigDataStore.data.map { prefs ->
        AppConfig(
            unlocked = prefs[unlockedKey] ?: false,
            usageSceneMode = normalizeUsageSceneMode(prefs[usageSceneModeKey].orEmpty()),
            formatStrength = normalizeFormatStrength(prefs[formatStrengthKey].orEmpty()),
            englishStyle = normalizeEnglishStyle(prefs[englishStyleKey].orEmpty()),
            recognitionNoiseFilterEnabled = prefs[recognitionNoiseFilterEnabledKey] ?: true,
            asrModelTreeUri = prefs[asrModelTreeUriKey].orEmpty(),
            llmModelUri = prefs[llmModelUriKey].orEmpty()
        )
    }

    suspend fun getConfigOnce(): AppConfig = configFlow.first()

    suspend fun unlockWithPasscode(passcode: String): Boolean {
        val normalized = passcode.trim()
        if (normalized != requiredPasscode) {
            return false
        }
        context.appConfigDataStore.edit { prefs ->
            prefs[unlockedKey] = true
        }
        return true
    }

    suspend fun lock() {
        context.appConfigDataStore.edit { prefs ->
            prefs[unlockedKey] = false
        }
    }

    suspend fun setUsageSceneMode(value: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs[usageSceneModeKey] = normalizeUsageSceneMode(value)
        }
    }

    suspend fun setFormatStrength(value: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs[formatStrengthKey] = normalizeFormatStrength(value)
        }
    }

    suspend fun setEnglishStyle(value: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs[englishStyleKey] = normalizeEnglishStyle(value)
        }
    }

    suspend fun setRecognitionNoiseFilterEnabled(enabled: Boolean) {
        context.appConfigDataStore.edit { prefs ->
            prefs[recognitionNoiseFilterEnabledKey] = enabled
        }
    }

    suspend fun setAsrModelTreeUri(uri: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs[asrModelTreeUriKey] = uri.trim()
        }
    }

    suspend fun setLlmModelUri(uri: String) {
        context.appConfigDataStore.edit { prefs ->
            prefs[llmModelUriKey] = uri.trim()
        }
    }

    data class AppConfig(
        val unlocked: Boolean,
        val usageSceneMode: String,
        val formatStrength: String,
        val englishStyle: String,
        val recognitionNoiseFilterEnabled: Boolean,
        val asrModelTreeUri: String,
        val llmModelUri: String
    )

    companion object {
        const val requiredPasscode = "0623"
        private const val defaultFormatStrength = "normal"
        private const val defaultEnglishStyle = "natural"
    }

    private fun normalizeFormatStrength(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            "light", "normal", "strong" -> value.trim().lowercase(Locale.US)
            else -> defaultFormatStrength
        }
    }

    private fun normalizeEnglishStyle(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            "natural", "casual", "formal" -> value.trim().lowercase(Locale.US)
            else -> defaultEnglishStyle
        }
    }

    private fun normalizeUsageSceneMode(value: String): String {
        return UsageSceneMode.fromConfig(value.trim().lowercase(Locale.US)).configValue
    }
}
