package com.toraonsei.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechController(
    private val context: Context,
    private val callback: Callback
) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String, alternatives: List<String>)
        fun onError(message: String)
        fun onEnd()
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callback.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            callback.onError(errorToMessage(error))
            callback.onEnd()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val text = alternatives.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                callback.onFinal(text, alternatives)
            }
            callback.onEnd()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                callback.onPartial(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
        }
    }

    fun startListening(biasHints: List<String> = emptyList()) {
        if (listening) return
        val engine = recognizer ?: run {
            callback.onError("音声認識エンジンが利用できません")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (biasHints.isNotEmpty()) {
                putStringArrayListExtra(
                    "android.speech.extra.BIASING_STRINGS",
                    ArrayList(biasHints.distinct().take(80))
                )
                putExtra("android.speech.extra.ENABLE_BIASING_DEVICE_CONTEXT", true)
            }
        }
        listening = true
        try {
            engine.startListening(intent)
        } catch (e: RuntimeException) {
            listening = false
            callback.onError("音声認識の開始に失敗しました")
            callback.onEnd()
        }
    }

    fun stopListening() {
        if (!listening) return
        recognizer?.stopListening()
    }

    fun cancel() {
        listening = false
        recognizer?.cancel()
    }

    fun destroy() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorToMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "音声入力エラー(マイク)"
            SpeechRecognizer.ERROR_CLIENT -> "音声入力エラー(クライアント)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限がありません"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークエラー"
            SpeechRecognizer.ERROR_NO_MATCH -> "音声を認識できませんでした"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンがビジーです"
            SpeechRecognizer.ERROR_SERVER -> "認識サーバーエラー"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "入力待機タイムアウト"
            else -> "音声入力エラー($error)"
        }
    }
}
