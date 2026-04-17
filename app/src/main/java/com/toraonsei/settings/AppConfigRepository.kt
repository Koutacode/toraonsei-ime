package com.toraonsei.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toraonsei.engine.UsageSceneMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.appConfigDataStore by preferencesDataStore(name = "app_config")

class AppConfigRepository(private val context: Context) {

    private val unlockedKey = booleanPreferencesKey("app_unlocked")
    private val usageSceneModeKey = stringPreferencesKey("usage_scene_mode")
    private val asrModelTreeUriKey = stringPreferencesKey("asr_model_tree_uri")
    private val llmModelUriKey = stringPreferencesKey("llm_model_uri")

    val configFlow: Flow<AppConfig> = context.appConfigDataStore.data.map { prefs ->
        AppConfig(
            unlocked = prefs[unlockedKey] ?: false,
            usageSceneMode = normalizeUsageSceneMode(prefs[usageSceneModeKey].orEmpty()),
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
        val asrModelTreeUri: String,
        val llmModelUri: String
    )

    companion object {
        const val requiredPasscode = "0623"
    }

    private fun normalizeUsageSceneMode(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            UsageSceneMode.WORK.configValue -> UsageSceneMode.WORK.configValue
            UsageSceneMode.MESSAGE.configValue -> UsageSceneMode.MESSAGE.configValue
            else -> UsageSceneMode.MESSAGE.configValue
        }
    }
}
