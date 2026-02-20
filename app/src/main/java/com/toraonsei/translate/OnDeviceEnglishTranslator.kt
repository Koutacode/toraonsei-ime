package com.toraonsei.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

class OnDeviceEnglishTranslator {

    data class Result(
        val text: String,
        val reason: String
    )

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )
    private val modelMutex = Mutex()
    @Volatile
    private var modelReady: Boolean = false

    suspend fun ensureModelReady(timeoutMs: Long = modelDownloadTimeoutMs): Boolean {
        if (modelReady) return true
        return modelMutex.withLock {
            if (modelReady) return@withLock true
            val downloaded = withTimeoutOrNull(timeoutMs) {
                translator.downloadModelIfNeeded(
                    DownloadConditions.Builder().build()
                ).await()
                true
            } ?: false
            modelReady = downloaded
            downloaded
        }
    }

    suspend fun translate(text: String): Result {
        val input = text.trim()
        if (input.isBlank()) {
            return Result(text = "", reason = "empty_input")
        }

        if (!ensureModelReady()) {
            return Result(text = "", reason = "model_not_ready")
        }

        val translated = withTimeoutOrNull(translateTimeoutMs) {
            translator.translate(input).await().trim()
        }.orEmpty()

        if (translated.isBlank()) {
            return Result(text = "", reason = "empty_output")
        }
        return Result(text = translated, reason = "ok")
    }

    fun close() {
        translator.close()
    }

    private companion object {
        const val modelDownloadTimeoutMs = 2400L
        const val translateTimeoutMs = 2600L
    }
}
