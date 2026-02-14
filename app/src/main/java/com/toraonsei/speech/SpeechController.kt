package com.toraonsei.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class SpeechController(
    context: Context,
    private val callback: Callback
) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String, alternatives: List<String>)
        fun onError(message: String)
        fun onEnd()
    }

    private enum class EngineMode {
        NONE,
        SHERPA_LOCAL,
        SYSTEM_RECOGNIZER
    }

    private data class LocalRecognizerPool(
        val fastRootPath: String,
        val accurateRootPath: String?,
        val fastRecognizer: OfflineRecognizer,
        val accurateRecognizer: OfflineRecognizer?,
        val fastLock: Any = Any(),
        val accurateLock: Any = Any()
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var engineMode: EngineMode = EngineMode.NONE
    private var localSession: LocalSherpaSession? = null
    private var localAsrCooldownUntilMs: Long = 0L
    private var localAsrFailureCount: Int = 0
    private var localRecognizerPool: LocalRecognizerPool? = null
    @Volatile
    private var localPrewarmInFlight: Boolean = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            dispatchReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            engineMode = EngineMode.NONE
            dispatchError(errorToMessage(error))
            dispatchEnd()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            engineMode = EngineMode.NONE
            val alternatives = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val text = alternatives.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                dispatchFinal(text, alternatives)
            }
            dispatchEnd()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) {
                dispatchPartial(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer?.setRecognitionListener(listener)
        }
    }

    fun startListening(biasHints: List<String> = emptyList()) {
        if (listening) return
        if (startSystemRecognizer(biasHints)) {
            return
        }
        dispatchError("音声認識エンジンを開始できません")
        dispatchEnd()
    }

    fun stopListening() {
        if (!listening) return
        when (engineMode) {
            EngineMode.SHERPA_LOCAL -> localSession?.stop(cancel = false)
            EngineMode.SYSTEM_RECOGNIZER -> recognizer?.stopListening()
            EngineMode.NONE -> Unit
        }
    }

    fun cancel() {
        listening = false
        when (engineMode) {
            EngineMode.SHERPA_LOCAL -> localSession?.stop(cancel = true)
            EngineMode.SYSTEM_RECOGNIZER -> recognizer?.cancel()
            EngineMode.NONE -> Unit
        }
        engineMode = EngineMode.NONE
    }

    fun destroy() {
        cancel()
        localSession?.release()
        localSession = null
        releaseLocalRecognizerPool()
        recognizer?.destroy()
        recognizer = null
    }

    fun prewarmLocalRecognizer() {
        // ローカルASRは現在未使用（システムASR固定運用）。
    }

    private fun startSherpaSession(status: SherpaAsrModelSupport.ModelStatus): Boolean {
        val pool = runCatching { ensureLocalRecognizerPool(status) }.getOrNull() ?: return false
        val session = LocalSherpaSession(pool)
        if (!session.start()) {
            session.release()
            return false
        }
        localSession?.release()
        localSession = session
        listening = true
        engineMode = EngineMode.SHERPA_LOCAL
        return true
    }

    private fun ensureLocalRecognizerPool(
        status: SherpaAsrModelSupport.ModelStatus
    ): LocalRecognizerPool {
        val fastModel = status.fast ?: error("fast model missing")
        val accurateModel = status.accurate
            ?.takeIf { it.rootDir.absolutePath != fastModel.rootDir.absolutePath }

        val fastRoot = fastModel.rootDir.absolutePath
        val accurateRoot = accurateModel?.rootDir?.absolutePath

        val current = localRecognizerPool
        if (
            current != null &&
            current.fastRootPath == fastRoot &&
            current.accurateRootPath == accurateRoot
        ) {
            return current
        }

        releaseLocalRecognizerPool()

        val fastRecognizer = createOfflineRecognizer(fastModel)
        val accurateRecognizer = accurateModel?.let { createOfflineRecognizer(it) }
        return LocalRecognizerPool(
            fastRootPath = fastRoot,
            accurateRootPath = accurateRoot,
            fastRecognizer = fastRecognizer,
            accurateRecognizer = accurateRecognizer
        ).also { created ->
            localRecognizerPool = created
        }
    }

    private fun releaseLocalRecognizerPool() {
        val pool = localRecognizerPool ?: return
        localRecognizerPool = null
        runCatching { pool.fastRecognizer.release() }
        pool.accurateRecognizer
            ?.takeIf { it !== pool.fastRecognizer }
            ?.let { runCatching { it.release() } }
    }

    private fun createOfflineRecognizer(
        model: SherpaAsrModelSupport.ResolvedModel
    ): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            modelConfig = model.modelConfig
        )
        return OfflineRecognizer(config = config)
    }

    private fun startSystemRecognizer(biasHints: List<String>): Boolean {
        val engine = recognizer ?: run {
            return false
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 速度優先: オンライン/オフラインの最適経路をシステムに任せる。
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (biasHints.isNotEmpty()) {
                putStringArrayListExtra(
                    "android.speech.extra.BIASING_STRINGS",
                    ArrayList(biasHints.distinct().take(24))
                )
                putExtra("android.speech.extra.ENABLE_BIASING_DEVICE_CONTEXT", true)
            }
        }
        listening = true
        engineMode = EngineMode.SYSTEM_RECOGNIZER
        try {
            engine.startListening(intent)
            return true
        } catch (_: RuntimeException) {
            listening = false
            engineMode = EngineMode.NONE
            return false
        }
    }

    private inner class LocalSherpaSession(
        private val pool: LocalRecognizerPool
    ) {
        private val sampleRate = 16000
        private val minFinalSamples = sampleRate / 3
        private val minPartialSamples = sampleRate / 2
        private val partialIntervalMs = 550L
        private val partialTailSamples = sampleRate * 4
        private val quickFinalTailSamples = sampleRate * 8

        private val stopRequested = AtomicBoolean(false)
        private val cancelRequested = AtomicBoolean(false)
        private val pcmBuffer = FloatPcmBuffer()

        private var captureThread: Thread? = null
        private var audioRecord: AudioRecord? = null
        private var lastPartial = ""
        private var lastPartialEmitMs = 0L

        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                return false
            }
            val captureBufferBytes = max(minBuffer * 2, sampleRate / 2)
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    captureBufferBytes
                )
            }.getOrNull() ?: run {
                return false
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            audioRecord = recorder

            captureThread = Thread({
                runCaptureLoop(captureBufferBytes / 2)
            }, "toraonsei-local-asr").apply {
                isDaemon = true
                start()
            }
            return true
        }

        fun stop(cancel: Boolean) {
            if (cancel) {
                cancelRequested.set(true)
            }
            if (!stopRequested.getAndSet(true)) {
                runCatching { audioRecord?.stop() }
            }
        }

        fun release() {
            stopRequested.set(true)
            runCatching { audioRecord?.stop() }
            runCatching { captureThread?.join(180) }
            runCatching { audioRecord?.release() }
            audioRecord = null
            captureThread = null
        }

        private fun runCaptureLoop(bufferSizeShorts: Int) {
            val recorder = audioRecord
            if (recorder == null) {
                markLocalAsrFailure()
                listening = false
                engineMode = EngineMode.NONE
                dispatchError("ローカル録音の初期化に失敗しました")
                dispatchEnd()
                return
            }

            var fatalError: String? = null
            val readBuffer = ShortArray(max(bufferSizeShorts, 1024))

            val startOk = runCatching {
                recorder.startRecording()
            }.isSuccess
            if (!startOk) {
                markLocalAsrFailure()
                listening = false
                engineMode = EngineMode.NONE
                dispatchError("録音開始に失敗しました")
                dispatchEnd()
                return
            }

            dispatchReady()

            while (!stopRequested.get()) {
                val read = recorder.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING
                )
                when {
                    read > 0 -> {
                        pcmBuffer.append(readBuffer, read)
                        maybeEmitPartial()
                    }

                    read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        markLocalAsrFailure()
                        fatalError = "ローカル音声認識エラー(録音)"
                        stopRequested.set(true)
                    }
                }
            }

            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            audioRecord = null

            val isCancel = cancelRequested.get()
            val finalAlternatives = if (!isCancel && pcmBuffer.size >= minFinalSamples) {
                decodeFinalWithReevaluation()
            } else {
                emptyList()
            }
            val japaneseLikelyAlternatives = finalAlternatives.filter {
                isLikelyJapaneseTranscript(it)
            }
            if (!isCancel && japaneseLikelyAlternatives.isNotEmpty()) {
                clearLocalAsrFailure()
                dispatchFinal(japaneseLikelyAlternatives.first(), japaneseLikelyAlternatives)
            } else if (!isCancel && finalAlternatives.isNotEmpty()) {
                markLocalAsrFailure()
                fatalError = "ローカルASR失敗: システム認識へ切替します"
            } else if (!isCancel && fatalError == null) {
                markLocalAsrFailure()
                fatalError = if (pcmBuffer.size < minFinalSamples) {
                    "音声が短すぎます"
                } else {
                    "ローカルASR失敗: システム認識へ切替します"
                }
            }

            listening = false
            engineMode = EngineMode.NONE

            if (!isCancel && !fatalError.isNullOrBlank()) {
                dispatchError(fatalError)
            }
            dispatchEnd()
        }

        private fun maybeEmitPartial() {
            if (stopRequested.get()) return
            if (pcmBuffer.size < minPartialSamples) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastPartialEmitMs < partialIntervalMs) return
            val fast = pool.fastRecognizer
            lastPartialEmitMs = now

            val partialInput = pcmBuffer.snapshotTail(partialTailSamples)
            if (partialInput.isEmpty()) return
            val partial = decodeWithRecognizer(
                recognizer = fast,
                samples = partialInput,
                decodeLock = pool.fastLock
            )
            if (
                partial.isNotBlank() &&
                partial != lastPartial &&
                isLikelyJapaneseTranscript(partial, allowNumericOnly = true)
            ) {
                lastPartial = partial
                dispatchPartial(partial)
            }
        }

        private fun decodeFinalWithReevaluation(): List<String> {
            val quickTail = pcmBuffer.snapshotTail(quickFinalTailSamples)
            val fastText = decodeWithRecognizer(
                recognizer = pool.fastRecognizer,
                samples = quickTail,
                decodeLock = pool.fastLock
            )
            return linkedSetOf<String>().apply {
                if (fastText.isNotBlank()) add(fastText)
                if (lastPartial.isNotBlank()) add(lastPartial)
            }.toList()
        }

        private fun decodeWithRecognizer(
            recognizer: OfflineRecognizer?,
            samples: FloatArray,
            decodeLock: Any
        ): String {
            if (recognizer == null || samples.isEmpty()) return ""
            return synchronized(decodeLock) {
                runCatching {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, sampleRate)
                        recognizer.decode(stream)
                        normalizeAsrText(recognizer.getResult(stream).text)
                    } finally {
                        runCatching { stream.release() }
                    }
                }.getOrDefault("")
            }
        }
    }

    private class FloatPcmBuffer(initialCapacity: Int = 16000 * 16) {
        private var data = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(input: ShortArray, count: Int) {
            if (count <= 0) return
            ensureCapacity(size + count)
            var outIndex = size
            for (index in 0 until count) {
                data[outIndex] = input[index] / 32768f
                outIndex += 1
            }
            size = outIndex
        }

        fun snapshot(): FloatArray {
            if (size == 0) return FloatArray(0)
            return data.copyOf(size)
        }

        fun snapshotTail(maxSamples: Int): FloatArray {
            if (size == 0) return FloatArray(0)
            if (maxSamples <= 0 || size <= maxSamples) {
                return data.copyOf(size)
            }
            return data.copyOfRange(size - maxSamples, size)
        }

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var next = data.size * 2
            while (next < required) {
                next *= 2
            }
            data = data.copyOf(next)
        }
    }

    private fun dispatchReady() {
        mainHandler.post { callback.onReady() }
    }

    private fun dispatchPartial(text: String) {
        mainHandler.post { callback.onPartial(text) }
    }

    private fun dispatchFinal(text: String, alternatives: List<String>) {
        mainHandler.post { callback.onFinal(text, alternatives) }
    }

    private fun dispatchError(message: String) {
        mainHandler.post { callback.onError(message) }
    }

    private fun dispatchEnd() {
        mainHandler.post { callback.onEnd() }
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

    private fun normalizeAsrText(text: String): String {
        return text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isLikelyJapaneseTranscript(
        text: String,
        allowNumericOnly: Boolean = false
    ): Boolean {
        val normalized = normalizeAsrText(text)
        if (normalized.isBlank()) return false
        val compact = normalized.filterNot { it.isWhitespace() }
        if (compact.isBlank()) return false
        if (allowNumericOnly && compact.matches(Regex("[0-9０-９.,:：/／+-]+"))) {
            return true
        }

        val jpCount = compact.count { ch ->
            ch in 'ぁ'..'ゖ' ||
                ch in 'ァ'..'ヺ' ||
                ch in '一'..'龯' ||
                ch == '々' ||
                ch == 'ー'
        }
        if (jpCount == 0) return false

        val ratio = jpCount.toFloat() / compact.length.toFloat()
        return ratio >= 0.28f
    }

    private fun markLocalAsrFailure() {
        localAsrFailureCount = (localAsrFailureCount + 1).coerceAtMost(maxLocalAsrFailuresBeforeCooldown + 3)
        localAsrCooldownUntilMs = SystemClock.elapsedRealtime() + localAsrCooldownMs
    }

    private fun clearLocalAsrFailure() {
        localAsrFailureCount = 0
    }

    private companion object {
        const val localAsrCooldownMs = 10 * 60 * 1000L
        const val maxLocalAsrFailuresBeforeCooldown = 2
    }
}
