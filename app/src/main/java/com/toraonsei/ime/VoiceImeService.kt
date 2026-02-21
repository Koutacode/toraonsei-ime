package com.toraonsei.ime

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.toraonsei.R
import com.toraonsei.dict.LocalKanaKanjiDictionary
import com.toraonsei.dict.UserDictionaryRepository
import com.toraonsei.engine.HybridLocalInferenceEngine
import com.toraonsei.engine.LocalLlmInferenceEngine
import com.toraonsei.engine.UsageSceneMode
import com.toraonsei.format.EnglishStyle
import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalFormatter
import com.toraonsei.model.DictionaryEntry
import com.toraonsei.model.RecentPhrase
import com.toraonsei.settings.AppConfigRepository
import com.toraonsei.settings.SettingsActivity
import com.toraonsei.speech.RecognitionTextCleaner
import com.toraonsei.speech.SpeechController
import com.toraonsei.suggest.SuggestionEngine
import com.toraonsei.translate.OnDeviceEnglishTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

import android.view.ContextThemeWrapper
import androidx.core.content.res.ResourcesCompat

class VoiceImeService : InputMethodService(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: UserDictionaryRepository
    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var localDictionary: LocalKanaKanjiDictionary
    private lateinit var speechController: SpeechController
    private lateinit var localLlmInferenceEngine: LocalLlmInferenceEngine
    private lateinit var onDeviceEnglishTranslator: OnDeviceEnglishTranslator

    private val suggestionEngine = SuggestionEngine()
    private val formatter = LocalFormatter()
    private val inferenceEngine = HybridLocalInferenceEngine(formatter)
    private val recognitionTextCleaner = RecognitionTextCleaner()

    private var dictionaryEntries: List<DictionaryEntry> = emptyList()
    private var recordingRequested = false
    private var isRecognizerActive = false
    private var appUnlocked = false
    private var recordingForegroundActive = false
    private var restartListeningJob: Job? = null
    private var finalizeVoiceCommitJob: Job? = null
    private var imeHeightSaveJob: Job? = null
    private var backspaceRepeatJob: Job? = null
    private var backspaceRepeatActive = false
    private var formatJob: Job? = null
    private var inputMode: InputMode = InputMode.KANA
    private var alphaUppercase = false
    private var usageSceneMode: UsageSceneMode = UsageSceneMode.MESSAGE
    private var formatAction: FormatAction = FormatAction.CONTEXT
    private var runtimeFormatStrength: FormatStrength = FormatStrength.NORMAL
    private var runtimeEnglishStyle: EnglishStyle = EnglishStyle.NATURAL
    private var runtimeRecognitionNoiseFilterEnabled: Boolean = true
    private var runtimeImeHeightScale: Float = 1.0f

    private var lastCommittedText: String = ""
    private var lastSelectedCandidate: String = ""
    private var lastVoiceCommittedText: String = ""
    private var lastRecognitionAlternatives: List<String> = emptyList()
    private var lastStatusMessage: String = ""
    private var voiceFinalizePending = false
    private val voiceSessionText = StringBuilder()
    private var lastVoiceSessionSegment = ""

    private val appMemory = mutableMapOf<String, AppBuffer>()
    private val formatActionMutex = Mutex()
    private val flickStartByButtonId = mutableMapOf<Int, Pair<Float, Float>>()
    private val flickLastDirectionByButtonId = mutableMapOf<Int, FlickDirection>()
    private var flickPopupWindow: PopupWindow? = null
    private var flickPopupText: TextView? = null
    private var toggleTapState: ToggleTapState? = null
    private var conversionState: ConversionState? = null

    private var rootView: View? = null
    private var keyboardRoot: ViewGroup? = null
    private var candidateScroll: HorizontalScrollView? = null
    private var recordRow: LinearLayout? = null
    private var manualInputPanel: LinearLayout? = null
    private var candidateContainer: LinearLayout? = null
    private var addWordButton: Button? = null
    private var clipboardPasteButton: Button? = null
    private var micButton: Button? = null
    private var formatContextButton: Button? = null
    private var statusText: TextView? = null
    private var imeHeightLabel: TextView? = null
    private var imeHeightSeekBar: SeekBar? = null
    private var imeHeightValueText: TextView? = null
    private var inputModeButton: Button? = null
    private var smallActionButton: Button? = null
    private var keyBackspaceButton: Button? = null
    private var enterKeyButton: Button? = null
    private var flickGuideText: TextView? = null
    private var tenKeyPanel: LinearLayout? = null
    private var clipboardManager: ClipboardManager? = null

    private var addWordPanel: LinearLayout? = null
    private var addWordEdit: EditText? = null
    private var addReadingEdit: EditText? = null
    private var saveWordButton: Button? = null
    private var cancelWordButton: Button? = null
    private var addWordTargetField: AddWordField = AddWordField.READING

    private var candidateChipMinWidthPx: Int = 0
    private var candidateChipHeightPx: Int = 0
    private var candidateChipTextSizeSp: Float = 13f
    private var flickKeyTextSizeSp: Float = 12.5f
    private var flickKeyPaddingPx: Int = 0
    private var flickDeadZonePx: Int = 0
    private var isImeHeightSeekBarTracking = false

    override fun onCreate() {
        super.onCreate()
        repository = UserDictionaryRepository(this)
        appConfigRepository = AppConfigRepository(this)
        localDictionary = LocalKanaKanjiDictionary(this)
        speechController = SpeechController(this, this)
        localLlmInferenceEngine = LocalLlmInferenceEngine(this)
        onDeviceEnglishTranslator = OnDeviceEnglishTranslator()
        clipboardManager = getSystemService(ClipboardManager::class.java)
        serviceScope.launch(Dispatchers.IO) {
            prewarmOnDeviceEnglishModel()
        }

        serviceScope.launch {
            appConfigRepository.configFlow.collectLatest { config ->
                val wasUnlocked = appUnlocked
                appUnlocked = config.unlocked
                usageSceneMode = UsageSceneMode.fromConfig(config.usageSceneMode)
                runtimeFormatStrength = FormatStrength.fromConfig(config.formatStrength)
                runtimeEnglishStyle = EnglishStyle.fromConfig(config.englishStyle)
                runtimeRecognitionNoiseFilterEnabled = config.recognitionNoiseFilterEnabled
                runtimeImeHeightScale = config.imeHeightScale
                updateUsageSceneModeUi()
                if (!isImeHeightSeekBarTracking) {
                    updateImeHeightSeekBarUi()
                }
                rootView?.post { applyImeSizingPolicy() }
                
                if (appUnlocked && !wasUnlocked) {
                    // ロック解除時: 一旦クリアして、次にキーボードが出る時に確実に新UIになるようにする
                    // Serviceの内部状態をリセット
                    serviceScope.launch(Dispatchers.Main) {
                        try {
                            // 再生成してセットし直す（ContextThemeWrapper経由）
                            val newView = onCreateInputView()
                            setInputView(newView)
                            setStatus("ロック解除されました")
                        } catch (e: Exception) {
                            setStatus("UIの初期化に失敗しました。開き直してください")
                        }
                    }
                }
                
                if (!appUnlocked && wasUnlocked) {
                    stopRecordingIfNeeded(forceCancel = true)
                }
            }
        }

        serviceScope.launch {
            repository.entriesFlow.collectLatest { entries ->
                dictionaryEntries = entries
                refreshSuggestions()
            }
        }

        serviceScope.launch {
            try {
                localDictionary.loadIfNeeded()
            } catch (_: Exception) {
                // 辞書アセットの読み込みに失敗してもIME入力自体は継続する。
            }
        }
    }

    override fun onCreateInputView(): View {
        clearBoundViews()
        val themeContext = ContextThemeWrapper(this, R.style.Theme_ToraOnsei_Ime)
        val inflater = LayoutInflater.from(themeContext)

        if (!appUnlocked) {
            val lockedView = inflater.inflate(R.layout.keyboard_locked_view, null)
            rootView = lockedView
            lockedView.findViewById<Button>(R.id.openSettingsUnlockButton)?.setOnClickListener {
                openSettingsForUnlock()
            }
            return lockedView
        }
        val view = inflater.inflate(R.layout.keyboard_view, null)
        rootView = view
        bindViews(view)
        setupListeners()
        applyManualKeyboardMode()
        updateEnterKeyUi(currentInputEditorInfo)
        applyImeSizingPolicy()
        updateMicState()
        refreshSuggestions()
        view.post {
            updateEnterKeyUi(currentInputEditorInfo)
            applyImeSizingPolicy()
            refreshSuggestions()
        }
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!appUnlocked) return
        updateEnterKeyUi(attribute)
        serviceScope.launch {
            try {
                localDictionary.loadIfNeeded()
            } catch (_: Exception) {
                // ローカル辞書更新失敗時は同梱辞書のまま継続。
            }
        }
        refreshSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!appUnlocked) return
        updateEnterKeyUi(info)
        applyImeSizingPolicy()
        updateMicState()
        setStatus(if (recordingRequested) "録音中: 話し終えたらボタンで終了" else "待機中")
        refreshSuggestions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootView?.post { applyImeSizingPolicy() }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        if (!appUnlocked) return
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        dismissFlickPopup()
        stopRecordingIfNeeded(forceCancel = true)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopRecordingIfNeeded(forceCancel = true)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        dismissFlickPopup()
        stopRecordingIfNeeded(forceCancel = true)
    }

    override fun onDestroy() {
        stopRecordingIfNeeded(forceCancel = true)
        restartListeningJob?.cancel()
        finalizeVoiceCommitJob?.cancel()
        imeHeightSaveJob?.cancel()
        backspaceRepeatActive = false
        backspaceRepeatJob?.cancel()
        formatJob?.cancel()
        dismissFlickPopup()
        speechController.destroy()
        localLlmInferenceEngine.release()
        onDeviceEnglishTranslator.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onReady() {
        isRecognizerActive = true
        setStatus("録音中: 話し終えたらボタンで終了")
    }

    override fun onPartial(text: String) {
        val cleaned = normalizeRecognitionText(text, partial = true)
        if (cleaned.isNotBlank()) {
            if (recordingRequested || voiceFinalizePending) {
                val length = buildVoiceSessionPreviewText(cleaned).length
                setStatus("認識中... (${length}文字)")
            } else {
                setStatus("認識中(途中結果)")
            }
        }
    }

    override fun onFinal(text: String, alternatives: List<String>) {
        val rawAlternatives = alternatives
            .ifEmpty { listOf(text) }
            .map { normalizeRecognitionText(it, partial = false) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        lastRecognitionAlternatives = rawAlternatives
        val corrected = selectBestRecognitionCandidate(
            alternatives = rawAlternatives
        )
        if (corrected.isBlank()) {
            setStatus("認識結果が空でした")
            return
        }

        if (!recordingRequested && !voiceFinalizePending) {
            return
        }
        appendVoiceSessionSegment(corrected)
        setStatus("録音中... (${voiceSessionText.length}文字)")
    }

    override fun onError(message: String) {
        isRecognizerActive = false
        if (recordingRequested) {
            setStatus("録音継続中: 再開します")
        } else if (voiceFinalizePending && isBenignStopError(message)) {
            // 手動停止直後の NO_MATCH / TIMEOUT は想定内。エラー表示にせず反映処理を継続する。
            setStatus("録音停止: 反映中...")
        } else if (message == "音声入力エラー(クライアント)") {
            setStatus("待機中")
        } else {
            setStatus(message)
            showToast(message)
        }
    }

    override fun onEnd() {
        isRecognizerActive = false
        if (recordingRequested) {
            scheduleRestartListening()
        } else if (voiceFinalizePending) {
            finalizeVoiceSessionCommit()
        } else {
            updateMicState()
        }
    }

    private fun appendVoiceSessionSegment(segment: String) {
        val normalized = normalizeVoiceSegmentText(segment)
        if (normalized.isBlank()) return
        if (normalized == lastVoiceSessionSegment) return

        val current = normalizeVoiceSegmentText(voiceSessionText.toString())
        val merged = mergeVoiceSessionText(
            current = current,
            incoming = normalized
        )
        if (merged == current) {
            lastVoiceSessionSegment = normalized
            return
        }

        voiceSessionText.setLength(0)
        voiceSessionText.append(merged)
        lastVoiceSessionSegment = normalized
    }

    private fun buildVoiceSessionPreviewText(tail: String = ""): String {
        val base = normalizeVoiceSegmentText(voiceSessionText.toString())
        val extra = normalizeVoiceSegmentText(tail)
        if (base.isBlank()) return extra
        if (extra.isBlank()) return base
        return mergeVoiceSessionText(base, extra)
    }

    private fun resetVoiceSessionState() {
        voiceFinalizePending = false
        finalizeVoiceCommitJob?.cancel()
        voiceSessionText.setLength(0)
        lastVoiceSessionSegment = ""
    }

    private fun normalizeVoiceSegmentText(text: String): String {
        return VoiceSessionTextUtils.normalizeSegment(text)
    }

    private fun mergeVoiceSessionText(current: String, incoming: String): String {
        return VoiceSessionTextUtils.merge(current, incoming)
    }

    private fun normalizeRecognitionText(text: String, partial: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        if (!runtimeRecognitionNoiseFilterEnabled) return trimmed
        return if (partial) {
            recognitionTextCleaner.cleanPartial(trimmed)
        } else {
            recognitionTextCleaner.cleanFinal(trimmed)
        }
    }

    private fun bindViews(view: View) {
        keyboardRoot = view.findViewById(R.id.keyboardRoot)
        candidateScroll = view.findViewById(R.id.candidateScroll)
        recordRow = view.findViewById(R.id.recordRow)
        manualInputPanel = view.findViewById(R.id.manualInputPanel)
        candidateContainer = view.findViewById(R.id.candidateContainer)
        addWordButton = view.findViewById(R.id.addWordButton)
        clipboardPasteButton = view.findViewById(R.id.clipboardPasteButton)
        micButton = view.findViewById(R.id.micButton)
        formatContextButton = view.findViewById(R.id.formatContextButton)
        statusText = view.findViewById(R.id.statusText)
        imeHeightLabel = view.findViewById(R.id.imeHeightLabel)
        imeHeightSeekBar = view.findViewById(R.id.imeHeightSeekBar)
        imeHeightValueText = view.findViewById(R.id.imeHeightValueText)
        inputModeButton = view.findViewById(R.id.inputModeButton)
        keyBackspaceButton = view.findViewById(R.id.keyBackspaceButton)
        flickGuideText = view.findViewById(R.id.flickGuideText)
        tenKeyPanel = view.findViewById(R.id.tenKeyPanel)

        addWordPanel = view.findViewById(R.id.addWordPanel)
        addWordEdit = view.findViewById(R.id.addWordEdit)
        addReadingEdit = view.findViewById(R.id.addReadingEdit)
        saveWordButton = view.findViewById(R.id.saveWordButton)
        cancelWordButton = view.findViewById(R.id.cancelWordButton)
    }

    private fun clearBoundViews() {
        keyboardRoot = null
        candidateScroll = null
        recordRow = null
        manualInputPanel = null
        candidateContainer = null
        addWordButton = null
        clipboardPasteButton = null
        micButton = null
        formatContextButton = null
        statusText = null
        imeHeightLabel = null
        imeHeightSeekBar = null
        imeHeightValueText = null
        inputModeButton = null
        smallActionButton = null
        keyBackspaceButton = null
        enterKeyButton = null
        flickGuideText = null
        tenKeyPanel = null
        addWordPanel = null
        addWordEdit = null
        addReadingEdit = null
        saveWordButton = null
        cancelWordButton = null
        flickStartByButtonId.clear()
        flickLastDirectionByButtonId.clear()
        backspaceRepeatActive = false
        backspaceRepeatJob?.cancel()
        isImeHeightSeekBarTracking = false
    }

    private fun openSettingsForUnlock() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun setupListeners() {
        rootView?.let {
            bindTaggedKeyButtons(it)
            bindFlickTenKeys(it)
            styleFlickTenKeys(it)
        }

        addWordButton?.setOnClickListener { openAddWordPanel() }
        clipboardPasteButton?.setOnClickListener {
            if (!ensureUnlockedForInput()) return@setOnClickListener
            val clipText = getClipboardText()
            if (clipText.isBlank()) {
                setStatus("クリップボードは空です")
                return@setOnClickListener
            }
            commitTextDirect(clipText)
            lastSelectedCandidate = clipText
            setStatus("クリップボードを貼り付け")
        }
        clipboardPasteButton?.setOnLongClickListener {
            val clipText = getClipboardText()
            if (clipText.isBlank()) {
                setStatus("クリップボードは空です")
            } else {
                val preview = clipText
                    .replace('\n', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(maxClipboardPreviewChars)
                val suffix = if (clipText.length > maxClipboardPreviewChars) "…" else ""
                setStatus("貼り付け候補: $preview$suffix")
            }
            true
        }

        micButton?.setOnClickListener {
            if (recordingRequested) {
                stopRecordingIfNeeded()
            } else {
                startRecordingIfAllowed()
            }
        }

        formatContextButton?.setOnClickListener {
            showFormatActionMenu(it)
        }
        formatContextButton?.setOnLongClickListener {
            applyFormatAction(formatAction)
            true
        }

        setupBackspaceRepeatTouch()

        addReadingEdit?.doAfterTextChanged { editable ->
            saveWordButton?.isEnabled = !editable.isNullOrBlank()
        }

        addWordEdit?.showSoftInputOnFocus = false
        addReadingEdit?.showSoftInputOnFocus = false
        addWordEdit?.setOnClickListener { focusAddWordField(AddWordField.WORD) }
        addReadingEdit?.setOnClickListener { focusAddWordField(AddWordField.READING) }
        addWordEdit?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addWordTargetField = AddWordField.WORD
            }
        }
        addReadingEdit?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                addWordTargetField = AddWordField.READING
            }
        }

        saveWordButton?.setOnClickListener {
            val word = addWordEdit?.text?.toString().orEmpty().trim()
            val reading = addReadingEdit?.text?.toString().orEmpty().trim()
            if (word.isBlank()) {
                setStatus("単語が空です")
                return@setOnClickListener
            }
            if (reading.isBlank()) {
                setStatus("読み(かな)は必須です")
                return@setOnClickListener
            }
            serviceScope.launch {
                repository.upsert(word, reading, 0)
                setStatus("単語帳に保存")
                hideAddWordPanel()
            }
        }

        cancelWordButton?.setOnClickListener { hideAddWordPanel() }
        smallActionButton?.maxLines = 2
        smallActionButton?.includeFontPadding = false
        smallActionButton?.gravity = Gravity.CENTER
        imeHeightSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                runtimeImeHeightScale = imeHeightScaleFromProgress(progress)
                updateImeHeightValueLabel()
                applyImeSizingPolicy()
                schedulePersistImeHeightScale()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isImeHeightSeekBarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isImeHeightSeekBarTracking = false
                schedulePersistImeHeightScale(immediate = true)
            }
        })
        imeHeightLabel?.setOnClickListener {
            cycleImeHeightPreset()
        }
        imeHeightLabel?.setOnLongClickListener {
            applyImeHeightScalePreset(1.0f)
            true
        }
        imeHeightValueText?.setOnClickListener {
            cycleImeHeightPreset()
        }
        imeHeightValueText?.setOnLongClickListener {
            applyImeHeightScalePreset(1.0f)
            true
        }
        updateUsageSceneModeUi()
        updateFormatButtonUi()
        updateImeHeightSeekBarUi()
    }

    private fun updateImeHeightSeekBarUi() {
        val seekBar = imeHeightSeekBar ?: return
        val targetProgress = imeHeightProgressFromScale(runtimeImeHeightScale)
        if (seekBar.progress != targetProgress) {
            seekBar.progress = targetProgress
        }
        updateImeHeightValueLabel()
    }

    private fun updateImeHeightValueLabel() {
        val label = ((runtimeImeHeightScale * 100f).roundToInt()).coerceIn(78, 125)
        imeHeightValueText?.text = "$label%"
    }

    private fun imeHeightScaleFromProgress(progress: Int): Float {
        return ImeHeightScaleUtils.scaleFromProgress(progress)
    }

    private fun imeHeightProgressFromScale(scale: Float): Int {
        return ImeHeightScaleUtils.progressFromScale(scale)
    }

    private fun schedulePersistImeHeightScale(immediate: Boolean = false) {
        imeHeightSaveJob?.cancel()
        imeHeightSaveJob = serviceScope.launch {
            if (!immediate) {
                delay(120L)
            }
            appConfigRepository.setImeHeightScale(runtimeImeHeightScale)
            if (immediate) {
                val label = ((runtimeImeHeightScale * 100f).roundToInt()).coerceIn(78, 125)
                setStatus("キーボード高さ: ${label}%")
            }
        }
    }

    private fun cycleImeHeightPreset() {
        if (ImeHeightScaleUtils.presets.isEmpty()) return
        val current = runtimeImeHeightScale
        var nearestIndex = 0
        var nearestDiff = Float.MAX_VALUE
        ImeHeightScaleUtils.presets.forEachIndexed { index, preset ->
            val diff = abs(preset - current)
            if (diff < nearestDiff) {
                nearestDiff = diff
                nearestIndex = index
            }
        }
        val nextIndex = (nearestIndex + 1) % ImeHeightScaleUtils.presets.size
        applyImeHeightScalePreset(ImeHeightScaleUtils.presets[nextIndex])
    }

    private fun applyImeHeightScalePreset(scale: Float) {
        runtimeImeHeightScale = scale.coerceIn(ImeHeightScaleUtils.minScale, ImeHeightScaleUtils.maxScale)
        updateImeHeightSeekBarUi()
        applyImeSizingPolicy()
        schedulePersistImeHeightScale(immediate = true)
    }

    private fun setupBackspaceRepeatTouch() {
        val button = keyBackspaceButton ?: return
        val cancelSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
            .coerceAtLeast(dp(12).toFloat())
        button.setOnClickListener(null)
        button.setOnLongClickListener(null)
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyboardHaptic(view)
                    handleBackspace()
                    backspaceRepeatActive = true
                    backspaceRepeatJob?.cancel()
                    backspaceRepeatJob = serviceScope.launch {
                        delay(backspaceRepeatInitialDelayMs)
                        while (backspaceRepeatActive) {
                            handleBackspace()
                            delay(backspaceRepeatIntervalMs)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTouchInsideView(view, event, cancelSlopPx)) {
                        backspaceRepeatActive = false
                        backspaceRepeatJob?.cancel()
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    backspaceRepeatActive = false
                    backspaceRepeatJob?.cancel()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun isTouchInsideView(view: View, event: MotionEvent, slopPx: Float = 0f): Boolean {
        val x = event.x
        val y = event.y
        return x >= -slopPx &&
            x <= view.width.toFloat() + slopPx &&
            y >= -slopPx &&
            y <= view.height.toFloat() + slopPx
    }

    private fun performKeyboardHaptic(target: View? = null) {
        val anchor = target ?: rootView ?: return
        runCatching {
            anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun applyManualKeyboardMode() {
        tenKeyPanel?.isVisible = true
        flickGuideText?.isVisible = false
        updateModeUi()
        hideFlickGuide()
        dismissFlickPopup()
    }

    private fun bindTaggedKeyButtons(root: View) {
        if (root is Button) {
            val tag = root.tag as? String
            if (tag == "SMALL") {
                smallActionButton = root
            }
            if (tag == "ENTER") {
                enterKeyButton = root
            }
            if (!tag.isNullOrBlank() && !tag.startsWith("F")) {
                root.setOnClickListener { handleTaggedKeyInput(tag) }

                if (tag == "CONVERT") {
                    root.setOnLongClickListener {
                        applyFormatAction(formatAction)
                        true
                    }
                }
            }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                bindTaggedKeyButtons(root.getChildAt(i))
            }
        }
    }

    private fun bindFlickTenKeys(root: View) {
        if (root is Button) {
            val tag = root.tag as? String
            if (isFlickCapableTag(tag)) {
                val keyTag = tag ?: return
                root.setOnTouchListener { button, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            flickStartByButtonId[button.id] = event.x to event.y
                            flickLastDirectionByButtonId[button.id] = FlickDirection.CENTER
                            showFlickGuide(keyTag, FlickDirection.CENTER)
                            showFlickPopup(button, keyTag, FlickDirection.CENTER)
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val start = flickStartByButtonId[button.id] ?: (event.x to event.y)
                            val direction = resolveFlickDirection(
                                startX = start.first,
                                startY = start.second,
                                endX = event.x,
                                endY = event.y
                            )
                            val previous = flickLastDirectionByButtonId[button.id]
                            if (previous != direction) {
                                flickLastDirectionByButtonId[button.id] = direction
                                showFlickGuide(keyTag, direction)
                                showFlickPopup(button, keyTag, direction)
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val start = flickStartByButtonId.remove(button.id) ?: (event.x to event.y)
                            flickLastDirectionByButtonId.remove(button.id)
                            val direction = resolveFlickDirection(
                                startX = start.first,
                                startY = start.second,
                                endX = event.x,
                                endY = event.y
                            )
                            if (keyTag == "SMALL") {
                                handleSmallKeyGesture(direction)
                            } else if (direction == FlickDirection.CENTER) {
                                handleCenterTapToggle(keyTag)
                            } else {
                                clearToggleTapState()
                                val output = resolveFlickKana(keyTag, direction)
                                if (output.isNotBlank()) {
                                    commitTextDirect(output)
                                }
                            }
                            performKeyboardHaptic(button)
                            hideFlickGuide()
                            dismissFlickPopup()
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            flickStartByButtonId.remove(button.id)
                            flickLastDirectionByButtonId.remove(button.id)
                            hideFlickGuide()
                            dismissFlickPopup()
                            true
                        }

                        else -> true
                    }
                }
            }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                bindFlickTenKeys(root.getChildAt(i))
            }
        }
    }

    private fun styleFlickTenKeys(root: View) {
        if (root is Button) {
            val tag = root.tag as? String
            if (!tag.isNullOrBlank() && tag.startsWith("F")) {
                root.includeFontPadding = false
                root.maxLines = 3
                root.textSize = flickKeyTextSizeSp
                val pad = flickKeyPaddingPx.takeIf { it > 0 } ?: dp(2)
                root.setPadding(pad, pad, pad, pad)
                root.text = buildFlickKeyLabel(tag)
            } else if (tag == "SMALL") {
                root.includeFontPadding = false
                root.maxLines = 2
                val pad = flickKeyPaddingPx.takeIf { it > 0 } ?: dp(2)
                root.setPadding(pad, pad, pad, pad)
            }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                styleFlickTenKeys(root.getChildAt(i))
            }
        }
    }

    private fun resolveFlickDirection(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): FlickDirection {
        val dx = endX - startX
        val dy = endY - startY
        val deadZonePx = flickDeadZonePx.takeIf { it > 0 } ?: dp(14)
        if (abs(dx) < deadZonePx && abs(dy) < deadZonePx) {
            return FlickDirection.CENTER
        }
        if (abs(dx) > abs(dy)) {
            return if (dx > 0f) FlickDirection.RIGHT else FlickDirection.LEFT
        }
        return if (dy > 0f) FlickDirection.DOWN else FlickDirection.UP
    }

    private fun resolveFlickKana(tag: String, direction: FlickDirection): String {
        val table = activeFlickMap()[tag] ?: return ""
        return when (direction) {
            FlickDirection.CENTER -> table[0]
            FlickDirection.LEFT -> table[1]
            FlickDirection.UP -> table[2]
            FlickDirection.RIGHT -> table[3]
            FlickDirection.DOWN -> table[4]
        }
    }

    private fun handleTaggedKeyInput(tag: String) {
        if (!ensureUnlockedForInput()) return
        if (tag != "BACKSPACE") {
            performKeyboardHaptic()
        }
        clearToggleTapState()
        if (tag != "CONVERT") {
            clearConversionState()
        }
        when (tag) {
            "SPACE" -> commitTextDirect(" ")
            "ENTER" -> performEditorActionOrEnter()
            "BACKSPACE" -> handleBackspace()
            "CONVERT" -> convertReadingToCandidate()
            "CURSOR_LEFT" -> {
                if (!moveCursorInAddWordEditor(-1)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                }
            }
            "CURSOR_RIGHT" -> {
                if (!moveCursorInAddWordEditor(1)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            "MODE_CYCLE" -> cycleInputMode()
            "LANG_PICKER" -> showInputMethodPickerSafely()
            "DAKUTEN" -> applyDakuten()
            "HANDAKUTEN" -> applyHandakuten()
            "SMALL" -> handleSmallAction()
            else -> commitTextDirect(tag)
        }
    }

    private fun performEditorActionOrEnter() {
        if (currentAddWordEditor() != null) {
            commitTextDirect("\n")
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val isActionKey = !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (isActionKey && ic.performEditorAction(action)) {
            return
        }
        commitTextDirect("\n")
    }

    private fun updateEnterKeyUi(info: EditorInfo?) {
        val button = enterKeyButton ?: return
        val actionLabel = resolveEnterActionLabel(info)
        val isAction = actionLabel != null
        button.text = actionLabel ?: "⏎"
        button.textSize = if (isAction) 14f else 20f
    }

    private fun resolveEnterActionLabel(info: EditorInfo?): String? {
        if (info == null) return null
        val custom = info.actionLabel?.toString()?.trim().orEmpty()
        if (custom.isNotBlank()) return custom.take(8)
        val imeOptions = info.imeOptions
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return null
        }
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "検索"
            EditorInfo.IME_ACTION_SEND -> "送信"
            EditorInfo.IME_ACTION_GO -> "移動"
            EditorInfo.IME_ACTION_NEXT -> "次へ"
            EditorInfo.IME_ACTION_DONE -> "完了"
            EditorInfo.IME_ACTION_PREVIOUS -> "前へ"
            else -> null
        }
    }

    private fun handleSmallAction() {
        when (inputMode) {
            InputMode.KANA -> applySmallKeyTapKana()
            InputMode.ALPHA -> toggleAlphaCase()
            InputMode.NUMERIC -> commitTextDirect("…")
        }
    }

    private fun handleCenterTapToggle(tag: String) {
        val table = activeFlickMap()[tag] ?: return
        val now = System.currentTimeMillis()
        val existing = toggleTapState

        if (
            existing != null &&
            existing.tag == tag &&
            now - existing.timestampMs <= toggleTapWindowMs
        ) {
            val nextIndex = (existing.index + 1) % table.size
            val nextText = table[nextIndex]
            val replaced = replaceToggleCommittedText(existing.output, nextText)
            if (replaced) {
                toggleTapState = ToggleTapState(
                    tag = tag,
                    index = nextIndex,
                    timestampMs = now,
                    output = nextText
                )
                showFlickGuide(tag, directionFromIndex(nextIndex))
                return
            }
        }

        val first = table[0]
        commitTextDirect(first)
        toggleTapState = ToggleTapState(
            tag = tag,
            index = 0,
            timestampMs = now,
            output = first
        )
        showFlickGuide(tag, FlickDirection.CENTER)
    }

    private fun replaceToggleCommittedText(previous: String, next: String): Boolean {
        val editor = currentAddWordEditor()
        if (editor != null) {
            return replaceToggleTextInEditor(editor, previous, next)
        }
        val ic = currentInputConnection ?: return false
        if (previous.isBlank()) return false

        val before = ic.getTextBeforeCursor(previous.length, 0)?.toString().orEmpty()
        if (!before.endsWith(previous)) {
            return false
        }

        ic.deleteSurroundingText(previous.length, 0)
        ic.commitText(next, 1)
        rememberCommittedText(next)
        refreshSuggestions()
        return true
    }

    private fun replaceToggleTextInEditor(editor: EditText, previous: String, next: String): Boolean {
        if (previous.isBlank()) return false
        val editable = editor.text ?: return false
        val cursor = editor.selectionStart.takeIf { it >= 0 } ?: editable.length
        if (cursor < previous.length) return false
        val start = cursor - previous.length
        val before = editable.subSequence(start, cursor).toString()
        if (before != previous) return false
        editable.replace(start, cursor, next)
        val nextCursor = (start + next.length).coerceAtMost(editable.length)
        editor.setSelection(nextCursor)
        if (editor === addReadingEdit) {
            saveWordButton?.isEnabled = editable.isNotBlank()
        }
        return true
    }

    private fun clearToggleTapState() {
        toggleTapState = null
    }

    private fun clearConversionState() {
        conversionState = null
    }

    private fun cycleInputMode() {
        inputMode = when (inputMode) {
            InputMode.KANA -> InputMode.ALPHA
            InputMode.ALPHA -> InputMode.NUMERIC
            InputMode.NUMERIC -> InputMode.KANA
        }
        if (inputMode != InputMode.ALPHA) {
            alphaUppercase = false
        }
        clearToggleTapState()
        clearConversionState()
        updateModeUi()
        rootView?.let { styleFlickTenKeys(it) }
        hideFlickGuide()
        setStatus(
            when (inputMode) {
                InputMode.KANA -> "入力モード: かな"
                InputMode.ALPHA -> "入力モード: 英字"
                InputMode.NUMERIC -> "入力モード: 数字/記号"
            }
        )
    }

    private fun toggleAlphaCase() {
        if (inputMode != InputMode.ALPHA) return
        alphaUppercase = !alphaUppercase
        clearToggleTapState()
        clearConversionState()
        updateModeUi()
        rootView?.let { styleFlickTenKeys(it) }
        setStatus(
            if (alphaUppercase) "英字: 大文字" else "英字: 小文字"
        )
    }

    private fun updateModeUi() {
        inputModeButton?.text = when (inputMode) {
            InputMode.KANA -> "あ/A/1"
            InputMode.ALPHA -> "A/1/あ"
            InputMode.NUMERIC -> "1/あ/A"
        }
        smallActionButton?.text = when (inputMode) {
            InputMode.KANA -> "゛゜\n小"
            InputMode.ALPHA -> "A/a"
            InputMode.NUMERIC -> "…"
        }
        updateUsageSceneModeUi()
    }

    private fun updateUsageSceneModeUi() {
        // 会話/仕事ボタンは非表示化したため、UI更新は不要。
    }

    private fun activeFlickMap(): Map<String, Array<String>> {
        return when (inputMode) {
            InputMode.KANA -> kanaFlickMap
            InputMode.ALPHA -> buildAlphaFlickMap()
            InputMode.NUMERIC -> numericFlickMap
        }
    }

    private fun activeRowLabelMap(): Map<String, String> {
        return when (inputMode) {
            InputMode.KANA -> kanaRowLabelMap
            InputMode.ALPHA -> alphaRowLabelMap
            InputMode.NUMERIC -> numericRowLabelMap
        }
    }

    private fun defaultGuideTextForMode(): String {
        return when (inputMode) {
            InputMode.KANA -> "タップ連打: あ→い→う→え→お / フリック: 左=い 上=う 右=え 下=お / 小キー: タップ=゛/゜ 左=゛ 右=゜ 下=小"
            InputMode.ALPHA -> "英字配列: abc/def... / フリック: 左→上→右→下 / A/aで大文字切替"
            InputMode.NUMERIC -> "数字配列: 1-9/0 / フリックで記号入力 / …キーで省略記号"
        }
    }

    private fun showFlickGuide(tag: String, direction: FlickDirection) {
        val table = resolveGuideTable(tag) ?: return
        val rowLabel = resolveGuideRowLabel(tag)

        val items = listOf(
            buildGuideItem("左", table[1], direction == FlickDirection.LEFT),
            buildGuideItem("上", table[2], direction == FlickDirection.UP),
            buildGuideItem("中", table[0], direction == FlickDirection.CENTER),
            buildGuideItem("右", table[3], direction == FlickDirection.RIGHT),
            buildGuideItem("下", table[4], direction == FlickDirection.DOWN)
        )

        val prefix = if (rowLabel.isNotBlank()) "$rowLabel  " else ""
        flickGuideText?.text = prefix + items.joinToString("  ")
    }

    private fun hideFlickGuide() {
        flickGuideText?.text = defaultGuideTextForMode()
    }

    private fun buildGuideItem(label: String, value: String, selected: Boolean): String {
        val base = "$label:$value"
        return if (selected) "【$base】" else base
    }

    private fun buildFlickKeyLabel(tag: String): CharSequence {
        if (inputMode == InputMode.ALPHA) {
            return buildAlphaCompactKeyLabel(tag)
        }
        if (inputMode == InputMode.NUMERIC) {
            return buildNumericCompactKeyLabel(tag)
        }
        val table = activeFlickMap()[tag] ?: return ""
        val parts = buildFlickLabelParts(table)
        return SpannableString(parts.text).apply {
            applySpan(RelativeSizeSpan(0.68f), parts.up)
            applySpan(RelativeSizeSpan(0.68f), parts.left)
            applySpan(RelativeSizeSpan(0.68f), parts.right)
            applySpan(RelativeSizeSpan(0.68f), parts.down)
            applySpan(RelativeSizeSpan(1.18f), parts.center)
            applySpan(StyleSpan(Typeface.BOLD), parts.center)
            applySpan(ForegroundColorSpan(0xFFF8FAFC.toInt()), parts.center)
        }
    }

    private fun buildAlphaCompactKeyLabel(tag: String): String {
        val base = alphaCompactLabelMap[tag] ?: return ""
        if (!alphaUppercase) return base
        return base.map { ch ->
            if (ch in 'a'..'z') ch.uppercaseChar() else ch
        }.joinToString("")
    }

    private fun buildNumericCompactKeyLabel(tag: String): String {
        return numericCompactLabelMap[tag] ?: ""
    }

    private fun buildFlickPopupLabel(tag: String, direction: FlickDirection): CharSequence {
        val table = resolveGuideTable(tag) ?: return ""
        val parts = buildFlickLabelParts(table)
        val selected = when (direction) {
            FlickDirection.CENTER -> parts.center
            FlickDirection.LEFT -> parts.left
            FlickDirection.UP -> parts.up
            FlickDirection.RIGHT -> parts.right
            FlickDirection.DOWN -> parts.down
        }

        return SpannableString(parts.text).apply {
            applySpan(RelativeSizeSpan(1.18f), parts.up)
            applySpan(RelativeSizeSpan(1.18f), parts.left)
            applySpan(RelativeSizeSpan(1.18f), parts.right)
            applySpan(RelativeSizeSpan(1.18f), parts.down)
            applySpan(RelativeSizeSpan(1.58f), parts.center)
            applySpan(StyleSpan(Typeface.BOLD), parts.center)
            applySpan(RelativeSizeSpan(1.64f), selected)
            applySpan(StyleSpan(Typeface.BOLD), selected)
            applySpan(BackgroundColorSpan(0xFF1D4ED8.toInt()), selected)
        }
    }

    private fun buildFlickLabelParts(table: Array<String>): FlickLabelParts {
        val sb = StringBuilder()
        val upStart = sb.length
        sb.append(table[2])
        val upEnd = sb.length

        sb.append('\n')
        val leftStart = sb.length
        sb.append(table[1])
        val leftEnd = sb.length

        sb.append(' ')
        val centerStart = sb.length
        sb.append(table[0])
        val centerEnd = sb.length

        sb.append(' ')
        val rightStart = sb.length
        sb.append(table[3])
        val rightEnd = sb.length

        sb.append('\n')
        val downStart = sb.length
        sb.append(table[4])
        val downEnd = sb.length

        return FlickLabelParts(
            text = sb.toString(),
            up = SpanRange(upStart, upEnd),
            left = SpanRange(leftStart, leftEnd),
            center = SpanRange(centerStart, centerEnd),
            right = SpanRange(rightStart, rightEnd),
            down = SpanRange(downStart, downEnd)
        )
    }

    private fun showFlickPopup(anchor: View, tag: String, direction: FlickDirection) {
        val popup = ensureFlickPopup() ?: return
        val popupTextView = flickPopupText ?: return
        popupTextView.text = buildFlickPopupLabel(tag, direction)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        popupTextView.measure(widthSpec, heightSpec)

        val popupWidth = popupTextView.measuredWidth.coerceAtLeast(dp(96))
        val popupHeight = popupTextView.measuredHeight.coerceAtLeast(dp(84))

        val parent = rootView ?: anchor
        val anchorInWindow = IntArray(2)
        val parentInWindow = IntArray(2)
        anchor.getLocationInWindow(anchorInWindow)
        parent.getLocationInWindow(parentInWindow)

        val parentWidth = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rawX = anchorInWindow[0] - parentInWindow[0] + (anchor.width - popupWidth) / 2
        val rawY = anchorInWindow[1] - parentInWindow[1] - popupHeight - dp(8)
        val x = rawX.coerceIn(dp(4), (parentWidth - popupWidth - dp(4)).coerceAtLeast(dp(4)))
        val y = rawY.coerceAtLeast(dp(4))

        try {
            if (popup.isShowing) {
                popup.update(x, y, popupWidth, popupHeight)
            } else {
                popup.showAtLocation(parent, Gravity.TOP or Gravity.START, x, y)
            }
        } catch (_: RuntimeException) {
            // IMEウィンドウ再生成タイミングでは表示に失敗することがあるため無視。
        }
    }

    private fun ensureFlickPopup(): PopupWindow? {
        val existing = flickPopupWindow
        if (existing != null) {
            return existing
        }

        val popupTextView = TextView(this).apply {
            setBackgroundResource(R.drawable.bg_flick_popup)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            includeFontPadding = false
        }

        flickPopupText = popupTextView
        val window = PopupWindow(
            popupTextView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            isClippingEnabled = true
            elevation = dp(8).toFloat()
        }
        flickPopupWindow = window
        return window
    }

    private fun dismissFlickPopup() {
        try {
            flickPopupWindow?.dismiss()
        } catch (_: RuntimeException) {
            // dismiss失敗は無視
        }
    }

    private fun directionFromIndex(index: Int): FlickDirection {
        return when (index) {
            1 -> FlickDirection.LEFT
            2 -> FlickDirection.UP
            3 -> FlickDirection.RIGHT
            4 -> FlickDirection.DOWN
            else -> FlickDirection.CENTER
        }
    }

    private fun applyDakuten() {
        val changed = replacePreviousChar(dakutenMap)
        if (!changed) {
            setStatus("濁点を付けられる文字の直後で使ってください")
        }
    }

    private fun applyHandakuten() {
        val changed = replacePreviousChar(handakutenMap)
        if (!changed) {
            setStatus("半濁点を付けられる文字の直後で使ってください")
        }
    }

    private fun applySmallKana() {
        val changed = replacePreviousChar(smallKanaToggleMap)
        if (!changed) {
            setStatus("小文字にできる文字の直後で使ってください")
        }
    }

    private fun applySmallKeyTapKana() {
        val changed = replacePreviousChar { before ->
            // 優先順: 小文字 -> 濁点 -> 半濁点
            smallKanaBaseToSmallMap[before]
                ?: smallKanaSmallToBaseMap[before]?.let { base -> dakutenForwardMap[base] }
                ?: dakutenForwardMap[before]
                ?: handakutenForwardMap[before]
        }
        if (!changed) {
            setStatus("小文字/濁点/半濁点にできる文字の直後で使ってください")
        }
    }

    private fun handleSmallKeyGesture(direction: FlickDirection) {
        clearToggleTapState()
        clearConversionState()
        when (inputMode) {
            InputMode.KANA -> {
                when (direction) {
                    FlickDirection.CENTER -> applySmallKeyTapKana()
                    FlickDirection.LEFT -> applyDakuten()
                    FlickDirection.RIGHT -> applyHandakuten()
                    else -> applySmallKana()
                }
            }
            else -> handleSmallAction()
        }
        hideFlickGuide()
        dismissFlickPopup()
    }

    private fun peekPreviousChar(): Char? {
        val editor = currentAddWordEditor()
        if (editor != null) {
            val editable = editor.text ?: return null
            val cursor = editor.selectionStart.takeIf { it >= 0 } ?: editable.length
            if (cursor <= 0 || cursor > editable.length) return null
            return editable[cursor - 1]
        }
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isBlank()) return null
        return before.last()
    }

    private fun replacePreviousChar(map: Map<Char, Char>): Boolean {
        return replacePreviousChar { before -> map[before] }
    }

    private fun replacePreviousChar(transform: (Char) -> Char?): Boolean {
        val editor = currentAddWordEditor()
        if (editor != null) {
            val editable = editor.text ?: return false
            val cursor = editor.selectionStart.takeIf { it >= 0 } ?: editable.length
            if (cursor <= 0) return false
            val before = editable[cursor - 1]
            val target = transform(before) ?: return false
            editable.replace(cursor - 1, cursor, target.toString())
            editor.setSelection(cursor)
            if (editor === addReadingEdit) {
                saveWordButton?.isEnabled = editable.isNotBlank()
            }
            return true
        }
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isEmpty()) return false
        val target = transform(before.last()) ?: return false
        ic.deleteSurroundingText(1, 0)
        ic.commitText(target.toString(), 1)
        refreshSuggestions()
        return true
    }

    private fun startRecordingIfAllowed() {
        if (!ensureUnlockedForInput()) return

        // 状態を完全にリセットしてから開始する
        stopRecordingIfNeeded(forceCancel = true)

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            setStatus("マイク権限がありません")
            showToast("設定画面でマイク権限を許可してください")
            return
        }

        resetVoiceSessionState()
        recordingRequested = true
        isRecognizerActive = false
        restartListeningJob?.cancel()
        updateMicState()
        ensureRecordingForeground()
        try {
            startListeningSession()
            setStatus("録音中: 話し終えたらボタンで終了")
        } catch (e: Exception) {
            setStatus("録音開始に失敗しました")
            recordingRequested = false
            updateMicState()
        }
    }

    private fun stopRecordingIfNeeded(forceCancel: Boolean = false) {
        if (!recordingRequested && !isRecognizerActive) {
            if (forceCancel) {
                resetVoiceSessionState()
            }
            return
        }
        val wasRecordingRequested = recordingRequested
        val recognizerWasActive = isRecognizerActive
        recordingRequested = false
        restartListeningJob?.cancel()
        if (forceCancel) {
            speechController.cancel()
            isRecognizerActive = false
            resetVoiceSessionState()
        } else {
            voiceFinalizePending = wasRecordingRequested
            speechController.stopListening()
            if (!recognizerWasActive && voiceFinalizePending) {
                finalizeVoiceSessionCommit()
            } else if (voiceFinalizePending) {
                finalizeVoiceCommitJob?.cancel()
                finalizeVoiceCommitJob = serviceScope.launch {
                    delay(850L)
                    if (!recordingRequested && voiceFinalizePending) {
                        finalizeVoiceSessionCommit()
                    }
                }
            }
        }
        updateMicState()
        stopRecordingForeground()
        setStatus(
            when {
                forceCancel -> "待機中"
                wasRecordingRequested -> "録音停止: 反映中..."
                else -> "待機中"
            }
        )
    }

    private fun finalizeVoiceSessionCommit() {
        if (!voiceFinalizePending) return
        voiceFinalizePending = false
        finalizeVoiceCommitJob?.cancel()
        val source = voiceSessionText.toString().trim()
        voiceSessionText.setLength(0)
        lastVoiceSessionSegment = ""
        if (source.isBlank()) {
            setStatus("待機中")
            return
        }

        val action = formatAction
        formatJob?.cancel()
        formatJob = serviceScope.launch {
            tryRecoverFormatMutexIfStale()
            if (!formatActionMutex.tryLock()) {
                commitAndTrack(source)
                lastVoiceCommittedText = source
                setStatus("前の処理中のため原文を貼り付けました")
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            setStatus("録音結果を処理中...")
            val slowJob = serviceScope.launch {
                delay(formatSlowStatusDelayMs)
                if (!recordingRequested) {
                    setStatus("処理中...時間がかかっています")
                }
            }
            try {
                Log.d(
                    logTag,
                    "voice-format-start action=${action.name.lowercase()} sourceLen=${source.length}"
                )
                val before = getCurrentBeforeCursor()
                val after = getCurrentAfterCursor()
                val pkg = currentPackageName()
                val history = appMemory[pkg]?.history?.toString().orEmpty()
                val dictionaryWordSet = dictionaryWordSet()
                val localResult = localFormat(
                    action = action,
                    input = source,
                    before = before,
                    after = after,
                    history = history,
                    dictionaryWordSet = dictionaryWordSet
                )
                val localFormatted = localResult.text
                val formatted = when (action) {
                    FormatAction.CONTEXT -> enforceJapanesePrimaryContextOutput(
                        source = source,
                        localFormatted = localFormatted,
                        candidate = localFormatted
                    )
                    FormatAction.ENGLISH -> localFormatted
                }.trim().ifBlank { source }

                commitAndTrack(formatted)
                lastVoiceCommittedText = formatted
                setStatus(
                    when (action) {
                        FormatAction.CONTEXT -> "録音結果を整形して貼り付けました"
                        FormatAction.ENGLISH -> "録音結果を英語翻訳して貼り付けました"
                    } + buildFormatStatusSuffix(localResult.note)
                )
            } finally {
                slowJob.cancel()
                runCatching { formatActionMutex.unlock() }
                Log.d(
                    logTag,
                    "voice-format-finish action=${action.name.lowercase()} elapsedMs=${System.currentTimeMillis() - startedAt}"
                )
            }
        }
    }

    private fun scheduleRestartListening() {
        restartListeningJob?.cancel()
        restartListeningJob = serviceScope.launch {
            delay(140)
            if (recordingRequested) {
                startListeningSession()
            }
        }
    }

    private fun startListeningSession() {
        speechController.startListening(buildSpeechBiasHints())
    }

    private fun buildSpeechBiasHints(): List<String> {
        val hints = LinkedHashSet<String>()

        dictionaryEntries
            .sortedByDescending { it.priority }
            .take(40)
            .forEach { entry ->
                val word = entry.word.trim()
                val reading = entry.readingKana.trim()
                if (word.isNotBlank()) hints.add(word)
                if (reading.isNotBlank()) hints.add(reading)
            }

        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        extractJapaneseTokens(before).forEach { hints.add(it) }
        extractJapaneseTokens(after).forEach { hints.add(it) }
        val readingTokens = extractJapaneseTokens(before + " " + after)
            .map { normalizeReadingKana(it) }
            .distinct()
        readingTokens.forEach { reading ->
            localDictionary.candidatesFor(reading, limit = 1).forEach { candidate ->
                hints.add(candidate)
            }
        }

        val pkg = currentPackageName()
        appMemory[pkg]
            ?.toRecentPhrases()
            ?.sortedByDescending { it.frequency }
            ?.take(10)
            ?.forEach { phrase ->
                if (phrase.text.isNotBlank()) {
                    hints.add(phrase.text)
                }
            }

        return hints.take(24)
    }

    private fun updateMicState() {
        val button = micButton ?: return
        if (recordingRequested) {
            button.text = "終了して貼付"
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.recording_active)
            )
        } else {
            button.text = "音声入力"
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
        }
    }

    private fun isFlickCapableTag(tag: String?): Boolean {
        if (tag.isNullOrBlank()) return false
        return tag.startsWith("F") || tag == "SMALL"
    }

    private fun resolveGuideTable(tag: String): Array<String>? {
        if (tag == "SMALL") {
            return when (inputMode) {
                InputMode.KANA -> arrayOf("゛/゜", "゛", "小", "゜", "小")
                InputMode.ALPHA -> arrayOf("A/a", "A/a", "A/a", "A/a", "A/a")
                InputMode.NUMERIC -> arrayOf("…", "…", "…", "…", "…")
            }
        }
        return activeFlickMap()[tag]
    }

    private fun resolveGuideRowLabel(tag: String): String {
        if (tag == "SMALL") {
            return when (inputMode) {
                InputMode.KANA -> "濁点/半濁点/小文字"
                InputMode.ALPHA -> "A/a"
                InputMode.NUMERIC -> "省略記号"
            }
        }
        return activeRowLabelMap()[tag].orEmpty()
    }

    @SuppressLint("InlinedApi")
    private fun ensureRecordingForeground() {
        if (!recordingRequested) return
        val notification = buildRecordingNotification() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    recordingNotificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(recordingNotificationId, notification)
            }
            recordingForegroundActive = true
        } catch (_: Exception) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(recordingNotificationId, notification)
            recordingForegroundActive = false
        }
    }

    private fun buildRecordingNotification(): android.app.Notification? {
        val manager = getSystemService(NotificationManager::class.java) ?: return null
        val channel = NotificationChannel(
            recordingNotificationChannelId,
            "録音状態",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "音声入力録音中の通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 1001, it, pendingFlags)
        }

        val notification = NotificationCompat.Builder(this, recordingNotificationChannelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ToraOnsei 録音中")
            .setContentText("再タップで停止。キーボードを閉じると停止")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()
        return notification
    }

    private fun stopRecordingForeground() {
        if (recordingForegroundActive) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            recordingForegroundActive = false
        }
        cancelRecordingNotification()
    }

    private fun cancelRecordingNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(recordingNotificationId)
    }

    private fun showFormatActionMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, FormatAction.CONTEXT.id, 0, "文章整形（文脈）")
        popup.menu.add(0, FormatAction.ENGLISH.id, 1, "英語翻訳")

        popup.setOnMenuItemClickListener { item ->
            val action = FormatAction.fromId(item.itemId) ?: return@setOnMenuItemClickListener false
            formatAction = action
            updateFormatButtonUi()
            applyFormatAction(action)
            true
        }
        popup.show()
    }

    private fun updateFormatButtonUi() {
        formatContextButton?.text = when (formatAction) {
            FormatAction.CONTEXT -> "変"
            FormatAction.ENGLISH -> "英"
        }
    }

    private fun applyFormatAction(action: FormatAction) {
        if (!ensureUnlockedForInput()) return
        if (currentAddWordEditor() != null) {
            setStatus("単語追加中は変換できません")
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty().trim()
        // 変/英 は長文でも変換できるよう、周辺テキスト取得範囲を広めに確保する。
        val before = getTextBeforeCursor(formatActionBeforeCursorMaxChars)
        val after = getTextAfterCursor(formatActionAfterCursorMaxChars)
        val pkg = currentPackageName()
        val history = appMemory[pkg]?.history?.toString().orEmpty()
        val inferredFromBefore = extractFormatSourceFromBeforeCursor(before)
        val source = when {
            selected.isNotBlank() -> selected
            inferredFromBefore.isNotBlank() -> inferredFromBefore
            lastVoiceCommittedText.isNotBlank() -> lastVoiceCommittedText
            else -> lastCommittedText
        }
        if (source.isBlank()) {
            setStatus("先に入力してから変換してください")
            return
        }
        val refinedSource = source
        val dictionaryWordSet = dictionaryWordSet()
        formatJob?.cancel()
        formatJob = serviceScope.launch {
            tryRecoverFormatMutexIfStale()
            if (!formatActionMutex.tryLock()) {
                setStatus("前の変換処理中です。少し待ってください")
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            try {
                Log.d(
                    logTag,
                    "manual-format-start action=${action.name.lowercase()} sourceLen=${refinedSource.length}"
                )
                val localResult = localFormat(
                    action = action,
                    input = refinedSource,
                    before = before,
                    after = after,
                    history = history,
                    dictionaryWordSet = dictionaryWordSet
                )
                val localFormatted = localResult.text

                val formatted = when (action) {
                    FormatAction.CONTEXT -> enforceJapanesePrimaryContextOutput(
                        source = refinedSource,
                        localFormatted = localFormatted,
                        candidate = localFormatted
                    )
                    FormatAction.ENGLISH -> localFormatted
                }
                val statusSuffix = buildFormatStatusSuffix(localResult.note)
                val successStatus = when (action) {
                    FormatAction.CONTEXT -> "文章を整形しました"
                    FormatAction.ENGLISH -> "英語に翻訳しました"
                } + statusSuffix
                val unchangedStatus = when (action) {
                    FormatAction.CONTEXT -> "文章整形: 変更なし"
                    FormatAction.ENGLISH -> "英語翻訳: 変更なし"
                } + statusSuffix

                applyFormattedResult(
                    source = source,
                    refinedSource = refinedSource,
                    selected = selected,
                    beforeSnapshot = before,
                    pkg = pkg,
                    formatted = formatted,
                    successStatus = successStatus,
                    unchangedStatus = unchangedStatus,
                    allowAppendOnMiss = action == FormatAction.CONTEXT
                )
            } finally {
                runCatching { formatActionMutex.unlock() }
                Log.d(
                    logTag,
                    "manual-format-finish action=${action.name.lowercase()} elapsedMs=${System.currentTimeMillis() - startedAt}"
                )
            }
        }
    }

    private fun tryRecoverFormatMutexIfStale() {
        val running = formatJob?.isActive == true
        if (running) return
        if (!formatActionMutex.isLocked) return
        val recovered = runCatching {
            formatActionMutex.unlock()
            true
        }.getOrDefault(false)
        if (recovered) {
            Log.w(logTag, "format mutex recovered from stale lock")
        }
    }

    private fun buildFormatStatusSuffix(note: String): String {
        val trimmed = note.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("LLM")) return ""
        return "（$trimmed）"
    }

    private suspend fun localFormat(
        action: FormatAction,
        input: String,
        before: String,
        after: String,
        history: String,
        dictionaryWordSet: Set<String>
    ): LocalFormatResult {
        return withContext(Dispatchers.Default) {
            try {
                when (action) {
                    FormatAction.CONTEXT -> {
                        val contextFormatted = inferenceEngine.infer(
                            HybridLocalInferenceEngine.Request(
                                input = input,
                                beforeCursor = before,
                                afterCursor = after,
                                appHistory = history,
                                dictionaryWords = dictionaryWordSet,
                                sceneMode = usageSceneMode,
                                trigger = HybridLocalInferenceEngine.Trigger.MANUAL_CONVERT,
                                strength = runtimeFormatStrength
                            )
                        )
                        val llmSource = contextFormatted.ifBlank { input }
                        val shouldRunLlm = shouldRunContextLlmRefinement(
                            source = llmSource,
                            strength = runtimeFormatStrength
                        )
                        val llmRefined = try {
                            if (shouldRunLlm) {
                                applyLocalLlmRefinement(
                                    source = llmSource,
                                    before = before,
                                    after = after,
                                    history = history
                                )
                            } else {
                                ""
                            }
                        } catch (e: Exception) {
                            ""
                        }
                        val output = llmRefined.ifBlank { contextFormatted }.ifBlank { input }.trim()
                        val note = when {
                            llmRefined.isNotBlank() -> "LLM整形"
                            shouldRunLlm.not() && contextFormatted.isNotBlank() && contextFormatted.trim() != input.trim() -> "高速ローカル整形"
                            shouldRunLlm.not() -> "高速モード"
                            contextFormatted.isNotBlank() && contextFormatted.trim() != input.trim() -> "ローカル整形"
                            else -> "原文維持"
                        }
                        LocalFormatResult(
                            text = output,
                            note = note
                        )
                    }
                    FormatAction.ENGLISH -> {
                        val translationStrength = resolveEnglishTranslationStrength(input)
                        val llmTranslated = try {
                            applyLocalLlmEnglishTranslation(
                                source = input,
                                history = history,
                                style = runtimeEnglishStyle,
                                strength = translationStrength
                            )
                        } catch (_: Exception) {
                            ""
                        }
                        val onDeviceTranslated = try {
                            applyOnDeviceEnglishTranslation(source = input)
                        } catch (_: Exception) {
                            ""
                        }

                        val fallback = formatter.toEnglishMessage(
                            input = input,
                            beforeCursor = before,
                            afterCursor = after,
                            appHistory = history,
                            style = runtimeEnglishStyle
                        )
                        val fallbackSanitized = sanitizeEnglishOutput(fallback)
                        val fallbackUsable = fallbackSanitized
                            .takeIf { isAcceptableEnglishFallback(source = input, candidate = it) }
                            .orEmpty()
                        val decision = chooseBestEnglishTranslation(
                            source = input,
                            llmCandidate = llmTranslated,
                            onDeviceCandidate = onDeviceTranslated,
                            fallbackCandidate = fallbackUsable,
                            fallbackRawCandidate = fallbackSanitized
                        )
                        val output = decision.text.ifBlank { input.trim() }
                        val note = when {
                            decision.source == EnglishTranslationSource.ON_DEVICE -> "端末翻訳"
                            decision.source == EnglishTranslationSource.LLM &&
                                translationStrength != runtimeFormatStrength -> "LLM翻訳(高速)"
                            decision.source == EnglishTranslationSource.LLM -> "LLM翻訳"
                            decision.source == EnglishTranslationSource.FALLBACK && output != input.trim() -> "辞書フォールバック"
                            decision.source == EnglishTranslationSource.FALLBACK_RECOVERY && output != input.trim() -> "辞書フォールバック(簡易)"
                            else -> "原文維持"
                        }
                        LocalFormatResult(
                            text = output,
                            note = note
                        )
                    }
                }
            } catch (e: Exception) {
                LocalFormatResult(
                    text = input,
                    note = "処理エラー"
                )
            }
        }
    }

    private suspend fun applyLocalLlmRefinement(
        source: String,
        before: String,
        after: String,
        history: String
    ): String {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.length >= localLlmRefineSkipInputChars && runtimeFormatStrength != FormatStrength.STRONG) {
            return ""
        }

        val chunks = chunkForLlmTranslation(trimmed, localLlmRefineChunkChars)
        if (chunks.isEmpty()) return ""
        val deadlineMs = System.currentTimeMillis() + localLlmRefineTotalBudgetMs

        val outputs = mutableListOf<String>()
        for (chunk in chunks) {
            val unit = chunk.trim()
            if (unit.isBlank()) continue
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= localLlmRefineMinRemainingMs) {
                outputs += unit
                continue
            }
            val timeoutMs = minOf(localLlmTimeoutMs, remainingMs)

            val llmResponse = withTimeoutOrNull(timeoutMs) {
                localLlmInferenceEngine.refine(
                    LocalLlmInferenceEngine.Request(
                        input = unit,
                        beforeCursor = before,
                        afterCursor = after,
                        appHistory = history,
                        sceneMode = usageSceneMode,
                        strength = runtimeFormatStrength
                    )
                )
            }

            val candidate = llmResponse?.takeIf { it.modelUsed }?.text?.trim().orEmpty()
            val accepted = candidate.isNotBlank() &&
                !(unit.length >= 10 && candidate.length > (unit.length * 1.95f + 18).toInt()) &&
                !(unit.length >= 10 && candidate.length < (unit.length * 0.38f).toInt()) &&
                !(japaneseCharRatio(unit) >= 0.45f && japaneseCharRatio(candidate) < 0.35f) &&
                !(unit.length >= 12 && charOverlapRatio(unit, candidate) < 0.30f)

            outputs += if (accepted) candidate else unit
        }

        val joiner = if (trimmed.contains('\n')) "\n" else " "
        return outputs.joinToString(joiner).trim()
    }

    private suspend fun applyLocalLlmEnglishTranslation(
        source: String,
        history: String,
        style: EnglishStyle,
        strength: FormatStrength
    ): String {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return ""

        val scene = when (style) {
            EnglishStyle.CASUAL -> UsageSceneMode.MESSAGE
            EnglishStyle.FORMAL -> UsageSceneMode.WORK
            EnglishStyle.NATURAL -> usageSceneMode
        }

        val chunks = chunkForLlmTranslation(trimmed, localLlmEnglishChunkChars)
        if (chunks.isEmpty()) return ""
        val deadlineMs = System.currentTimeMillis() + localLlmEnglishTotalBudgetMs

        val outputs = mutableListOf<String>()
        for (chunk in chunks) {
            val unit = chunk.trim()
            if (unit.isBlank()) continue
            val remainingMs = deadlineMs - System.currentTimeMillis()

            val translated = if (remainingMs > localLlmEnglishMinRemainingMs) {
                translateEnglishChunkRecursively(
                    source = unit,
                    history = history,
                    style = style,
                    scene = scene,
                    strength = strength,
                    maxChunkChars = localLlmEnglishChunkChars,
                    depth = 0,
                    deadlineMs = deadlineMs
                )
            } else {
                ""
            }
            val fallbackChunk = englishFallbackForUnit(
                unit = unit,
                history = history,
                style = style
            )
            val chosenChunk = translated.ifBlank { fallbackChunk }.trim()
            if (chosenChunk.isBlank()) {
                return ""
            }
            outputs += chosenChunk
        }

        val joiner = if (trimmed.contains('\n')) "\n" else " "
        return sanitizeEnglishOutput(outputs.joinToString(joiner).trim())
    }

    private suspend fun applyOnDeviceEnglishTranslation(source: String): String {
        val trimmed = source.trim()
        if (trimmed.isBlank() || !containsJapaneseChars(trimmed)) return ""

        val result = withTimeoutOrNull(onDeviceEnglishTimeoutMs) {
            onDeviceEnglishTranslator.translate(trimmed)
        } ?: return ""

        if (result.text.isBlank()) {
            Log.d(logTag, "on-device-translation-empty reason=${result.reason}")
            return ""
        }

        val sanitized = sanitizeEnglishOutput(result.text)
        val accepted = sanitized.takeIf {
            isAcceptableEnglishFallback(source = trimmed, candidate = it)
        }.orEmpty()
        if (accepted.isBlank()) {
            Log.d(logTag, "on-device-translation-rejected reason=${result.reason}")
        } else {
            Log.d(logTag, "on-device-translation-ok len=${accepted.length}")
        }
        return accepted
    }

    private suspend fun prewarmOnDeviceEnglishModel() {
        val ready = runCatching {
            onDeviceEnglishTranslator.ensureModelReady(onDeviceEnglishPrewarmTimeoutMs)
        }.getOrDefault(false)
        Log.d(logTag, "on-device-model-ready ready=$ready")
    }

    private suspend fun translateEnglishChunkRecursively(
        source: String,
        history: String,
        style: EnglishStyle,
        scene: UsageSceneMode,
        strength: FormatStrength,
        maxChunkChars: Int,
        depth: Int,
        deadlineMs: Long
    ): String {
        val unit = source.trim()
        if (unit.isBlank()) return ""
        if (deadlineMs - System.currentTimeMillis() <= localLlmEnglishMinRemainingMs) return ""

        val llm = requestLocalLlmEnglishChunk(
            unit = unit,
            history = history,
            scene = scene,
            strength = strength,
            deadlineMs = deadlineMs
        )
        if (llm.isNotBlank()) return llm

        val canSplit = depth < localLlmEnglishMaxSplitDepth &&
            unit.length >= localLlmEnglishSplitThresholdChars &&
            maxChunkChars > localLlmEnglishMinChunkChars
        if (canSplit) {
            val nextChunkChars = (maxChunkChars / 2).coerceAtLeast(localLlmEnglishMinChunkChars)
            val subChunks = chunkForLlmTranslation(unit, nextChunkChars)
            if (subChunks.size > 1) {
                val outputs = mutableListOf<String>()
                for (sub in subChunks) {
                    val translated = translateEnglishChunkRecursively(
                        source = sub,
                        history = history,
                        style = style,
                        scene = scene,
                        strength = strength,
                        maxChunkChars = nextChunkChars,
                        depth = depth + 1,
                        deadlineMs = deadlineMs
                    )
                    val fallbackUnit = englishFallbackForUnit(
                        unit = sub,
                        history = history,
                        style = style
                    )
                    val chosen = translated.ifBlank { fallbackUnit }.trim()
                    if (chosen.isBlank()) {
                        return ""
                    }
                    outputs += chosen
                }
                val joiner = if (unit.contains('\n')) "\n" else " "
                return sanitizeEnglishOutput(outputs.joinToString(joiner).trim())
            }
        }

        return englishFallbackForUnit(
            unit = unit,
            history = history,
            style = style
        )
    }

    private suspend fun requestLocalLlmEnglishChunk(
        unit: String,
        history: String,
        scene: UsageSceneMode,
        strength: FormatStrength,
        deadlineMs: Long
    ): String {
        repeat(localLlmEnglishChunkRetryCount) { attempt ->
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= localLlmEnglishMinRemainingMs) {
                return ""
            }
            val timeoutMs = minOf(localLlmEnglishTimeoutMs, remainingMs)
            val llmResponse = withTimeoutOrNull(timeoutMs) {
                // 翻訳は入力自体が文脈なので、追加文脈は最小限にしてトークン節約する。
                localLlmInferenceEngine.refine(
                    LocalLlmInferenceEngine.Request(
                        input = unit,
                        beforeCursor = "",
                        afterCursor = "",
                        appHistory = history,
                        sceneMode = scene,
                        strength = strength,
                        task = LocalLlmInferenceEngine.Task.TRANSLATE_ENGLISH
                    )
                )
            }

            val candidate = llmResponse
                ?.takeIf { it.modelUsed }
                ?.text
                ?.trim()
                .orEmpty()
            if (candidate.isNotBlank()) {
                val sanitized = sanitizeEnglishOutput(candidate)
                val sourceHasJapanese = containsJapaneseChars(unit)
                val hasJapaneseLeak = sourceHasJapanese && containsJapaneseChars(sanitized)
                if (sanitized.isNotBlank() && !hasJapaneseLeak) {
                    return sanitized
                }
                val japaneseRatio = japaneseCharRatio(sanitized)
                val latinRatio = latinCharRatio(sanitized)
                if (
                    sanitized.isNotBlank() &&
                    sourceHasJapanese &&
                    japaneseRatio <= localLlmFallbackMaxJapaneseRatio &&
                    latinRatio >= localLlmFallbackMinLatinRatio
                ) {
                    return sanitized
                }
                Log.d(
                    logTag,
                    "llm-translation-rejected attempt=${attempt + 1} " +
                        "reason=${llmResponse?.reason ?: "timeout"} " +
                        "jpRatio=$japaneseRatio latinRatio=$latinRatio"
                )
            } else {
                Log.d(
                    logTag,
                    "llm-translation-empty attempt=${attempt + 1} reason=${llmResponse?.reason ?: "timeout"}"
                )
            }

            if (attempt + 1 < localLlmEnglishChunkRetryCount) {
                if (deadlineMs - System.currentTimeMillis() <= localLlmEnglishMinRemainingMs) {
                    return ""
                }
                delay((attempt + 1) * localLlmEnglishRetryDelayMs)
            }
        }
        return ""
    }

    private fun englishFallbackForUnit(
        unit: String,
        history: String,
        style: EnglishStyle
    ): String {
        val fallback = formatter.toEnglishMessage(
            input = unit,
            beforeCursor = "",
            afterCursor = "",
            appHistory = history,
            style = style
        )
        val sanitized = sanitizeEnglishOutput(fallback)
        if (sanitized.isBlank()) return ""

        val fallbackJapaneseRatio = japaneseCharRatio(sanitized)
        val fallbackLatinRatio = latinCharRatio(sanitized)
        return sanitized.takeIf {
            it.isNotBlank() &&
                (
                    !containsJapaneseChars(it) ||
                        fallbackJapaneseRatio <= localLlmFallbackMaxJapaneseRatio ||
                        fallbackLatinRatio >= localLlmFallbackMinLatinRatio
                    )
        }.orEmpty()
    }

    private fun shouldRunContextLlmRefinement(
        source: String,
        strength: FormatStrength
    ): Boolean {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.length < 20) return false
        if (trimmed.length > localLlmRefineSkipInputChars && strength != FormatStrength.STRONG) {
            return false
        }
        if (strength == FormatStrength.STRONG) return true

        val punctuationCount = trimmed.count { ch ->
            ch == '。' || ch == '、' || ch == '！' || ch == '？' || ch == '\n'
        }
        val hasNoisyWhitespace = Regex("[\\t ]{2,}").containsMatchIn(trimmed)
        val longWithoutPunctuation = trimmed.length >= 90 && punctuationCount == 0
        return hasNoisyWhitespace || longWithoutPunctuation
    }

    private fun resolveEnglishTranslationStrength(source: String): FormatStrength {
        val len = source.trim().length
        return when {
            len >= 420 -> FormatStrength.LIGHT
            len >= 180 -> FormatStrength.NORMAL
            runtimeFormatStrength == FormatStrength.LIGHT -> FormatStrength.LIGHT
            else -> FormatStrength.NORMAL
        }
    }

    private fun chunkForLlmTranslation(text: String, maxChunkChars: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isBlank()) return emptyList()
        if (normalized.length <= maxChunkChars) return listOf(normalized)

        val segments = mutableListOf<String>()
        val segmentBuf = StringBuilder()
        fun flushSegment() {
            val s = segmentBuf.toString().trim()
            if (s.isNotBlank()) segments += s
            segmentBuf.setLength(0)
        }

        for (ch in normalized) {
            segmentBuf.append(ch)
            val boundary = ch == '\n' ||
                ch == '。' || ch == '！' || ch == '？' ||
                ch == '.' || ch == '!' || ch == '?'
            if (boundary && segmentBuf.length >= 48) {
                flushSegment()
            }
        }
        flushSegment()

        if (segments.isEmpty()) return listOf(normalized.take(maxChunkChars))

        val chunks = mutableListOf<String>()
        val chunkBuf = StringBuilder()
        fun flushChunk() {
            val s = chunkBuf.toString().trim()
            if (s.isNotBlank()) chunks += s
            chunkBuf.setLength(0)
        }

        for (seg in segments) {
            val trimmedSeg = seg.trim()
            if (trimmedSeg.isBlank()) continue

            if (trimmedSeg.length > maxChunkChars) {
                // 文単位でも長すぎる場合は、強制的に分割する。
                var start = 0
                while (start < trimmedSeg.length) {
                    val end = (start + maxChunkChars).coerceAtMost(trimmedSeg.length)
                    val part = trimmedSeg.substring(start, end).trim()
                    if (part.isNotBlank()) {
                        if (chunkBuf.isNotEmpty()) {
                            flushChunk()
                        }
                        chunks += part
                    }
                    start = end
                }
                continue
            }

            if (chunkBuf.isNotEmpty() && chunkBuf.length + 1 + trimmedSeg.length > maxChunkChars) {
                flushChunk()
            }
            if (chunkBuf.isNotEmpty()) chunkBuf.append('\n')
            chunkBuf.append(trimmedSeg)
        }
        flushChunk()

        return chunks.ifEmpty { listOf(normalized.take(maxChunkChars)) }
    }

    private fun dictionaryWordSet(): Set<String> {
        return dictionaryEntries
            .asSequence()
            .map { it.word.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun applyFormattedResult(
        source: String,
        refinedSource: String,
        selected: String,
        beforeSnapshot: String,
        pkg: String,
        formatted: String,
        successStatus: String,
        unchangedStatus: String,
        allowAppendOnMiss: Boolean
    ) {
        val normalizedFormatted = formatted.trim()

        if (normalizedFormatted.isBlank()) {
            setStatus("整形結果が空でした")
            return
        }
        if (normalizedFormatted == source && refinedSource == source) {
            setStatus(unchangedStatus)
            return
        }

        val icNow = currentInputConnection ?: return
        val beforeNow = getTextBeforeCursor(formatActionBeforeCursorMaxChars)
        var replaced = false
        if (selected.isNotBlank()) {
            icNow.commitText(normalizedFormatted, 1)
            replaced = true
        } else {
            val replaceTargets = linkedSetOf<String>()
            source.trim().takeIf { it.isNotBlank() }?.let { replaceTargets.add(it) }
            refinedSource.trim().takeIf { it.isNotBlank() }?.let { replaceTargets.add(it) }
            extractFormatSourceFromBeforeCursor(beforeNow)
                .takeIf { it.isNotBlank() }
                ?.let { replaceTargets.add(it) }

            for (target in replaceTargets.sortedByDescending { it.length }) {
                if (target.length > beforeNow.length) continue
                if (beforeNow.endsWith(target) || beforeSnapshot.endsWith(target)) {
                    icNow.deleteSurroundingText(target.length, 0)
                    icNow.commitText(normalizedFormatted, 1)
                    replaced = true
                    break
                }
            }
        }

        if (!replaced) {
            if (!allowAppendOnMiss) {
                setStatus("$unchangedStatus（置換対象が見つかりません）")
                return
            }
            icNow.commitText(normalizedFormatted, 1)
        }

        lastCommittedText = normalizedFormatted
        lastVoiceCommittedText = normalizedFormatted
        val memory = appMemory.getOrPut(pkg) { AppBuffer() }
        memory.appendHistory(normalizedFormatted)
        memory.addRecentPhrase(normalizedFormatted)
        refreshSuggestions()
        setStatus(successStatus)
    }

    private fun extractFormatSourceFromBeforeCursor(beforeCursor: String): String {
        if (beforeCursor.isBlank()) return ""
        val tail = beforeCursor.takeLast(formatSourceTailMaxChars).trimEnd()
        if (tail.isBlank()) return ""
        // 以前は「最終行のみ」を対象にしていたが、長文入力では先頭側が変換されないため
        // まとめて末尾ブロックを対象にする（必要なら選択範囲で明示的に指定できる）。
        return tail.trim().takeLast(formatSourceMaxChars).trim()
    }

    private fun sanitizeEnglishOutput(text: String): String {
        val trimmed = text
            .replace(Regex("(?im)^(translation|translated|output|result|input)\\s*[:：].*$"), "")
            .replace("<input>", "")
            .replace("</input>", "")
            .replace("<output>", "")
            .replace("</output>", "")
            .trim()
        if (trimmed.isBlank()) return ""
        val lines = trimmed
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        val englishOnlyLines = lines.filter { line ->
            line.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                !line.any { ch -> ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヺ' || ch in '一'..'龯' }
        }

        // 一部だけ英語化できたケースで「英語行だけ抽出」すると後半が消えて見えるため、
        // ほぼ全行が英語のみの場合に限って英語行だけを採用する。
        val useEnglishOnly = englishOnlyLines.isNotEmpty() &&
            englishOnlyLines.size >= (lines.size * 0.85f).toInt().coerceAtLeast(1)

        val chosen = if (useEnglishOnly) englishOnlyLines else lines
        return chosen
            .joinToString("\n")
            .replace(Regex("[\\t ]+"), " ")
            .trim()
    }

    private fun chooseBestEnglishTranslation(
        source: String,
        llmCandidate: String,
        onDeviceCandidate: String,
        fallbackCandidate: String,
        fallbackRawCandidate: String
    ): EnglishTranslationDecision {
        val sourceTrimmed = source.trim()
        val llm = sanitizeEnglishOutput(llmCandidate)
            .takeIf { isAcceptableEnglishFallback(source = sourceTrimmed, candidate = it) }
            .orEmpty()
        val onDevice = sanitizeEnglishOutput(onDeviceCandidate)
            .takeIf { isAcceptableEnglishFallback(source = sourceTrimmed, candidate = it) }
            .orEmpty()
        val fallback = sanitizeEnglishOutput(fallbackCandidate)
            .takeIf { isAcceptableEnglishFallback(source = sourceTrimmed, candidate = it) }
            .orEmpty()
        val fallbackRaw = sanitizeEnglishOutput(fallbackRawCandidate)
        val llmScore = englishTranslationScore(sourceTrimmed, llm)
        val onDeviceScore = englishTranslationScore(sourceTrimmed, onDevice)
        val fallbackScore = englishTranslationScore(sourceTrimmed, fallback)

        val candidates = mutableListOf<Triple<String, EnglishTranslationSource, Int>>()
        if (onDevice.isNotBlank()) {
            candidates += Triple(onDevice, EnglishTranslationSource.ON_DEVICE, onDeviceScore)
        }
        if (llm.isNotBlank()) {
            candidates += Triple(llm, EnglishTranslationSource.LLM, llmScore)
        }
        if (fallback.isNotBlank()) {
            candidates += Triple(fallback, EnglishTranslationSource.FALLBACK, fallbackScore)
        }

        val decision = when {
            candidates.isEmpty() -> {
                val recovered = recoverEnglishFallback(source = sourceTrimmed, fallbackRaw = fallbackRaw)
                if (recovered.isNotBlank()) {
                    EnglishTranslationDecision(
                        text = recovered,
                        source = EnglishTranslationSource.FALLBACK_RECOVERY,
                        llmScore = llmScore,
                        fallbackScore = fallbackScore,
                        onDeviceScore = onDeviceScore
                    )
                } else {
                    EnglishTranslationDecision(
                        text = sourceTrimmed,
                        source = EnglishTranslationSource.SOURCE,
                        llmScore = llmScore,
                        fallbackScore = fallbackScore,
                        onDeviceScore = onDeviceScore
                    )
                }
            }
            else -> {
                val best = candidates
                    .sortedWith(
                        compareByDescending<Triple<String, EnglishTranslationSource, Int>> { it.third }
                            .thenBy { englishSourcePriority(it.second) }
                    )
                    .first()
                EnglishTranslationDecision(
                    text = best.first,
                    source = best.second,
                    llmScore = llmScore,
                    fallbackScore = fallbackScore,
                    onDeviceScore = onDeviceScore
                )
            }
        }
        Log.d(
            logTag,
            "translation-choice source=${decision.source.name.lowercase()} " +
                "llmScore=${decision.llmScore} fallbackScore=${decision.fallbackScore} " +
                "onDeviceScore=${decision.onDeviceScore} " +
                "llmLen=${llm.length} onDeviceLen=${onDevice.length} " +
                "fallbackLen=${fallback.length} fallbackRawLen=${fallbackRaw.length}"
        )
        return decision
    }

    private fun englishSourcePriority(source: EnglishTranslationSource): Int {
        return when (source) {
            EnglishTranslationSource.ON_DEVICE -> 0
            EnglishTranslationSource.LLM -> 1
            EnglishTranslationSource.FALLBACK -> 2
            EnglishTranslationSource.FALLBACK_RECOVERY -> 3
            EnglishTranslationSource.SOURCE -> 4
        }
    }

    private fun recoverEnglishFallback(source: String, fallbackRaw: String): String {
        if (!containsJapaneseChars(source)) return ""
        if (fallbackRaw.isBlank()) return ""

        val stripped = fallbackRaw
            .map { ch ->
                if (
                    ch in 'ぁ'..'ゖ' ||
                    ch in 'ァ'..'ヺ' ||
                    ch in '一'..'龯' ||
                    ch == '々'
                ) ' ' else ch
            }
            .joinToString("")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\s+([,\\.\\?!])"), "$1")
            .trim()

        if (stripped.isBlank()) return ""
        if (latinCharRatio(stripped) < englishRecoveryMinLatinRatio) return ""

        val words = stripped
            .split(Regex("\\s+"))
            .filter { token -> token.any { it in 'A'..'Z' || it in 'a'..'z' } }
        if (words.size < 2) return ""
        return stripped
    }

    private fun englishTranslationScore(source: String, candidate: String): Int {
        val normalizedCandidate = sanitizeEnglishOutput(candidate)
        if (normalizedCandidate.isBlank()) return Int.MIN_VALUE / 4

        var score = 0
        val sourceTrimmed = source.trim()
        val sourceHasJapanese = containsJapaneseChars(sourceTrimmed)
        if (sourceHasJapanese &&
            !isAcceptableEnglishFallback(source = sourceTrimmed, candidate = normalizedCandidate)
        ) {
            return Int.MIN_VALUE / 4
        }
        val japaneseRatio = japaneseCharRatio(normalizedCandidate)
        val latinRatio = latinCharRatio(normalizedCandidate)

        score += (latinRatio * 120f).toInt()
        score -= (japaneseRatio * 220f).toInt()
        if (sourceHasJapanese && latinRatio < englishScoreMinLatinRatio) {
            score -= 90
        }
        if (sourceHasJapanese && japaneseRatio > englishScoreMaxJapaneseRatio) {
            score -= 120
        }

        val sourceLen = sourceTrimmed.filterNot { it.isWhitespace() }.length.coerceAtLeast(1)
        val outLen = normalizedCandidate.filterNot { it.isWhitespace() }.length
        val ratio = outLen.toFloat() / sourceLen.toFloat()
        if (sourceLen >= 12) {
            when {
                ratio < 0.33f -> score -= 85
                ratio < 0.48f -> score -= 35
                ratio > 2.8f -> score -= 70
                else -> score += 15
            }
        }

        if (englishMetaRegex.containsMatchIn(normalizedCandidate)) {
            score -= 95
        }
        if (englishRepeatedWordRegex.containsMatchIn(normalizedCandidate)) {
            score -= 70
        }

        val questionLike = formatter.isQuestionLike(sourceTrimmed)
        val outputEndsQuestion = normalizedCandidate.trimEnd().endsWith("?")
        when {
            questionLike && outputEndsQuestion -> score += 24
            questionLike && !outputEndsQuestion -> score -= 18
            !questionLike && outputEndsQuestion -> score -= 8
        }

        val requestLike = englishRequestSourceRegex.containsMatchIn(sourceTrimmed)
        val hasPleaseTone = englishPleaseRegex.containsMatchIn(normalizedCandidate)
        when {
            requestLike && hasPleaseTone -> score += 12
            requestLike && !hasPleaseTone -> score -= 6
        }

        val sourceNumberTokens = englishNumberTokenRegex.findAll(sourceTrimmed).map { it.value }.toSet()
        if (sourceNumberTokens.isNotEmpty()) {
            val missingCount = sourceNumberTokens.count { token ->
                !normalizedCandidate.contains(token)
            }
            score -= missingCount * 18
        }

        val englishWordCount = normalizedCandidate
            .split(Regex("\\s+"))
            .count { token -> token.any { it in 'A'..'Z' || it in 'a'..'z' } }
        if (sourceLen >= 18 && englishWordCount < 3) {
            score -= 80
        }
        return score
    }

    private fun getTextBeforeCursor(maxChars: Int): String {
        val ic = currentInputConnection ?: return ""
        return ic.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
    }

    private fun getTextAfterCursor(maxChars: Int): String {
        val ic = currentInputConnection ?: return ""
        return ic.getTextAfterCursor(maxChars, 0)?.toString().orEmpty()
    }

    private fun enforceJapanesePrimaryContextOutput(
        source: String,
        localFormatted: String,
        candidate: String
    ): String {
        val normalizedCandidate = candidate.trim()
        val normalizedLocal = localFormatted.trim()
        if (normalizedCandidate.isBlank()) {
            return normalizedLocal.ifBlank { source.trim() }
        }

        // 入力元が日本語主体のときは、変換結果も日本語を優先する。
        if (!containsJapaneseChars(source)) {
            return normalizedCandidate
        }
        if (containsJapaneseChars(normalizedCandidate)) {
            if (isLikelyNonJapaneseStyle(source, normalizedCandidate)) {
                return source.trim().ifBlank { normalizedLocal.ifBlank { normalizedCandidate } }
            }
            if (isAggressiveKanjiization(source = source, candidate = normalizedCandidate)) {
                return source.trim().ifBlank { normalizedLocal.ifBlank { normalizedCandidate } }
            }
            if (isExcessiveContextRewrite(source = source, candidate = normalizedCandidate)) {
                return source.trim().ifBlank { normalizedLocal.ifBlank { normalizedCandidate } }
            }
            return normalizedCandidate
        }

        if (normalizedLocal.isNotBlank() && containsJapaneseChars(normalizedLocal)) {
            return normalizedLocal
        }
        return source.trim().ifBlank { normalizedCandidate }
    }

    private fun isLikelyNonJapaneseContextOutput(source: String, candidate: String): Boolean {
        if (!containsJapaneseChars(source)) return false
        if (candidate.isBlank()) return true
        if (containsJapaneseChars(candidate)) return false

        val latinCount = candidate.count { ch -> ch in 'A'..'Z' || ch in 'a'..'z' }
        val digitCount = candidate.count { it.isDigit() }
        return latinCount >= 3 && latinCount > digitCount
    }

    private fun isLikelyNonJapaneseStyle(source: String, candidate: String): Boolean {
        if (!containsJapaneseChars(source) || !containsJapaneseChars(candidate)) return false

        val sourceJapaneseCount = countJapaneseChars(source)
        val candidateJapaneseCount = countJapaneseChars(candidate)
        if (sourceJapaneseCount <= 0 || candidateJapaneseCount <= 0) return false

        val sourceKanaCount = countKanaChars(source)
        val candidateKanaCount = countKanaChars(candidate)

        if (sourceKanaCount >= 4 && candidateKanaCount == 0) {
            return true
        }

        val sourceKanaRatio = sourceKanaCount.toFloat() / sourceJapaneseCount.toFloat()
        val candidateKanaRatio = candidateKanaCount.toFloat() / candidateJapaneseCount.toFloat()

        // 元文がひらがな主体なのに、結果だけ漢字過多（中国語寄り）になるケースを抑止。
        if (sourceKanaRatio >= 0.35f && candidateKanaRatio < 0.12f) {
            return true
        }

        return false
    }

    private fun isAggressiveKanjiization(source: String, candidate: String): Boolean {
        // ひらがな中心の入力が、急に漢字だらけへ変化した場合は不自然変換として弾く。
        if (containsKanjiChars(source)) return false

        val sourceJapaneseCount = countJapaneseChars(source)
        if (sourceJapaneseCount <= 0) return false
        val sourceKanaRatio = countKanaChars(source).toFloat() / sourceJapaneseCount.toFloat()

        val candidateJapaneseCount = countJapaneseChars(candidate)
        if (candidateJapaneseCount <= 0) return false
        val candidateDensity = kanjiDensity(candidate)
        val overlap = charOverlapRatio(source, candidate)

        if (sourceKanaRatio >= 0.70f && candidateDensity >= 0.46f) return true
        if (source.length >= 6 && overlap < 0.42f && candidateDensity >= 0.32f) return true
        if (source.length >= 8 && candidate.length > (source.length * 1.55f + 4).toInt() && candidateDensity >= 0.28f) return true
        return false
    }

    private fun isExcessiveContextRewrite(source: String, candidate: String): Boolean {
        // 入力に対して漢字比率や長さが急変する候補は、文脈整形の過変換として抑止する。
        if (source.isBlank() || candidate.isBlank()) return false
        if (!containsJapaneseChars(source) || !containsJapaneseChars(candidate)) return false

        val sourceJapaneseCount = countJapaneseChars(source)
        val candidateJapaneseCount = countJapaneseChars(candidate)
        if (sourceJapaneseCount <= 0 || candidateJapaneseCount <= 0) return false

        val sourceKanaRatio = countKanaChars(source).toFloat() / sourceJapaneseCount.toFloat()
        val candidateKanaRatio = countKanaChars(candidate).toFloat() / candidateJapaneseCount.toFloat()
        val sourceKanjiCount = source.count { it in '一'..'龯' || it == '々' }
        val candidateKanjiCount = candidate.count { it in '一'..'龯' || it == '々' }
        val overlap = charOverlapRatio(source, candidate)

        if (
            source.length >= 6 &&
            sourceKanaRatio >= 0.45f &&
            candidateKanaRatio <= 0.20f &&
            candidateKanjiCount >= sourceKanjiCount + 4 &&
            overlap < 0.68f
        ) {
            return true
        }

        if (
            source.length <= 14 &&
            candidateKanjiCount >= sourceKanjiCount + 3 &&
            overlap < 0.52f
        ) {
            return true
        }

        if (
            source.length >= 8 &&
            candidate.length > (source.length * 1.35f + 4).toInt() &&
            candidateKanjiCount >= sourceKanjiCount + 2
        ) {
            return true
        }

        return false
    }

    private fun kanjiDensity(text: String): Float {
        if (text.isBlank()) return 0f
        val japaneseChars = text.count { ch ->
            ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヺ' || ch in '一'..'龯' || ch == '々'
        }
        if (japaneseChars == 0) return 0f
        val kanji = text.count { ch -> ch in '一'..'龯' || ch == '々' }
        return kanji.toFloat() / japaneseChars.toFloat()
    }

    private fun containsKanjiChars(text: String): Boolean {
        return text.any { ch -> ch in '一'..'龯' || ch == '々' || ch == '〆' || ch == '〤' }
    }

    private fun countKanaChars(text: String): Int {
        return text.count { ch -> ch in 'ぁ'..'ゖ' || ch in 'ァ'..'ヺ' }
    }

    private fun countJapaneseChars(text: String): Int {
        return text.count { ch ->
            ch in 'ぁ'..'ゖ' ||
                ch in 'ァ'..'ヺ' ||
                ch in '一'..'龯' ||
                ch == '々' || ch == '〆' || ch == '〤'
        }
    }

    private fun containsJapaneseChars(text: String): Boolean {
        return text.any { ch ->
            ch in 'ぁ'..'ゖ' ||
                ch in 'ァ'..'ヺ' ||
                ch in '一'..'龯' ||
                ch == '々' || ch == '〆' || ch == '〤'
        }
    }

    private fun latinCharRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val plain = text.filterNot { it.isWhitespace() }
        if (plain.isBlank()) return 0f
        val latin = plain.count { it in 'A'..'Z' || it in 'a'..'z' }
        return latin.toFloat() / plain.length.toFloat()
    }

    private fun japaneseCharRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val plain = text.filterNot { it.isWhitespace() }
        if (plain.isBlank()) return 0f
        val japanese = countJapaneseChars(plain)
        return japanese.toFloat() / plain.length.toFloat()
    }

    private fun isBenignStopError(message: String): Boolean {
        if (message.isBlank()) return false
        return message.contains("認識できませんでした") ||
            message.contains("聞き取れ") ||
            message.contains("入力待機タイムアウト")
    }

    private fun isAcceptableEnglishFallback(source: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (!containsJapaneseChars(source)) return true
        if (!containsJapaneseChars(candidate)) return true

        return japaneseCharRatio(candidate) <= localLlmFallbackMaxJapaneseRatio &&
            latinCharRatio(candidate) >= localLlmFallbackMinLatinRatio
    }

    private fun charOverlapRatio(source: String, target: String): Float {
        if (source.isBlank()) return 1f
        if (target.isBlank()) return 0f
        val plainSource = source.filterNot { it.isWhitespace() }
        if (plainSource.isBlank()) return 1f
        val hit = plainSource.count { target.contains(it) }
        return hit.toFloat() / plainSource.length.toFloat()
    }

    private fun convertSurfaceText(
        input: String,
        beforeCursor: String,
        afterCursor: String,
        history: String,
        allowLocalDictionary: Boolean = true
    ): String {
        val source = input.trim()
        if (source.isBlank()) return ""
        if (!allowLocalDictionary && !shouldApplySafeSurfaceConversion(source)) {
            return source
        }

        val rules = buildSurfaceConversionRules(source, allowLocalDictionary)
        if (rules.isEmpty()) {
            return source
        }

        val out = StringBuilder()
        var cursor = 0
        surfaceKanaChunkRegex.findAll(source).forEach { match ->
            val start = match.range.first
            if (start > cursor) {
                out.append(source.substring(cursor, start))
            }
            val chunk = match.value
            val normalized = normalizeReadingKana(chunk)
            if (normalized.length < 2) {
                out.append(chunk)
                cursor = match.range.last + 1
                return@forEach
            }

            var converted = chunk
            val greedy = applyLongestRuleConversion(normalized, rules)
            if (greedy.replacedCount > 0 && greedy.output.isNotBlank()) {
                converted = greedy.output
            }

            val exactCandidates = buildConversionCandidates(
                reading = normalized,
                beforeCursor = beforeCursor,
                afterCursor = afterCursor,
                history = history
            )
            if (exactCandidates.isNotEmpty()) {
                val ranked = rankConversionCandidates(
                    reading = normalized,
                    candidates = linkedSetOf<String>().apply {
                        converted.trim().takeIf { it.isNotBlank() }?.let { add(it) }
                        exactCandidates.take(5).forEach { add(it) }
                    },
                    beforeCursor = beforeCursor,
                    afterCursor = afterCursor,
                    history = history,
                    preferredOrder = buildList {
                        converted.trim().takeIf { it.isNotBlank() }?.let { add(it) }
                        addAll(exactCandidates.take(5))
                    }
                )
                ranked.firstOrNull()?.takeIf { it.isNotBlank() }?.let { top ->
                    converted = top
                }
            }

            out.append(converted)
            cursor = match.range.last + 1
        }

        if (cursor < source.length) {
            out.append(source.substring(cursor))
        }
        return out.toString().trim()
    }

    private fun shouldApplySafeSurfaceConversion(text: String): Boolean {
        if (text.length > 40) return false
        if (text.contains('\n')) return false
        if (containsKanjiChars(text)) return false
        if (text.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }) return false

        val hasKatakana = text.any { it in 'ァ'..'ヺ' || it == 'ー' }
        val hasHiragana = text.any { it in 'ぁ'..'ゖ' }
        if (hasKatakana && hasHiragana && text.length >= 12) {
            return false
        }
        return true
    }

    private fun buildSurfaceConversionRules(input: String, allowLocalDictionary: Boolean): List<ConversionRule> {
        val merged = mutableListOf<ConversionRule>()
        merged.addAll(buildConversionRules())

        if (!allowLocalDictionary) {
            return merged
                .sortedWith(
                    compareByDescending<ConversionRule> { it.reading.length }
                        .thenByDescending { it.score }
                )
        }

        val readings = linkedSetOf<String>()
        surfaceKanaChunkRegex.findAll(input).forEach { match ->
            if (readings.size >= surfaceLocalLookupLimit) return@forEach
            val normalized = normalizeReadingKana(match.value)
            if (normalized.length < 2) return@forEach
            val maxLen = minOf(surfaceLocalLookupMaxReadingLength, normalized.length)
            for (len in maxLen downTo 2) {
                var start = 0
                while (start + len <= normalized.length) {
                    readings.add(normalized.substring(start, start + len))
                    if (readings.size >= surfaceLocalLookupLimit) {
                        break
                    }
                    start += 1
                }
                if (readings.size >= surfaceLocalLookupLimit) {
                    break
                }
            }
        }

        readings.forEach { reading ->
            localDictionary
                .candidatesFor(reading, limit = surfaceLocalCandidatePerReading)
                .forEachIndexed { index, candidate ->
                    val output = candidate.trim()
                    if (output.isBlank() || output == reading) return@forEachIndexed
                    merged.add(
                        ConversionRule(
                            reading = reading,
                            output = output,
                            score = (540 + reading.length * 10 - index * 22).coerceAtLeast(240)
                        )
                    )
                }
        }

        return merged
            .distinctBy { "${it.reading}|${it.output}" }
            .sortedWith(
                compareByDescending<ConversionRule> { it.reading.length }
                    .thenByDescending { it.score }
            )
    }

    private fun handleBackspace() {
        clearToggleTapState()
        clearConversionState()
        if (deleteFromAddWordEditor()) {
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        refreshSuggestions()
    }

    private fun commitTextDirect(text: String) {
        if (!ensureUnlockedForInput()) return
        if (text.isEmpty()) return
        clearConversionState()
        if (commitTextToAddWordEditor(text)) {
            return
        }
        currentInputConnection?.commitText(text, 1)
        rememberCommittedText(text)
        refreshSuggestions()
    }

    private fun ensureUnlockedForInput(): Boolean {
        if (appUnlocked) return true
        showToast("このIMEはロック中です。設定で0623を入力してください")
        return false
    }

    private fun setStatus(message: String) {
        if (lastStatusMessage == message) return
        if (statusText?.isVisible == true) {
            statusText?.text = message
        }
        lastStatusMessage = message
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openAddWordPanel() {
        val seed = when {
            lastSelectedCandidate.isNotBlank() -> lastSelectedCandidate
            lastCommittedText.isNotBlank() -> lastCommittedText
            else -> getCurrentBeforeCursor().takeLast(24)
        }.trim()

        addWordEdit?.setText(seed)
        addReadingEdit?.setText("")
        addWordPanel?.isVisible = true
        focusAddWordField(AddWordField.READING)
        saveWordButton?.isEnabled = false
        setStatus("読み(かな)を入力してください")
    }

    private fun hideAddWordPanel() {
        addWordPanel?.isVisible = false
        addWordEdit?.clearFocus()
        addReadingEdit?.clearFocus()
        addWordTargetField = AddWordField.READING
    }

    private fun focusAddWordField(target: AddWordField) {
        addWordTargetField = target
        val editor = when (target) {
            AddWordField.WORD -> addWordEdit
            AddWordField.READING -> addReadingEdit
        } ?: return

        editor.isFocusable = true
        editor.isFocusableInTouchMode = true
        editor.requestFocus()
        val text = editor.text
        editor.setSelection(text?.length ?: 0)
    }

    private fun currentAddWordEditor(): EditText? {
        if (addWordPanel?.isVisible != true) return null
        val reading = addReadingEdit
        val word = addWordEdit
        return when {
            reading?.hasFocus() == true -> reading
            word?.hasFocus() == true -> word
            addWordTargetField == AddWordField.WORD -> word
            else -> reading
        }
    }

    private fun commitTextToAddWordEditor(text: String): Boolean {
        val editor = currentAddWordEditor() ?: return false
        val editable = editor.text ?: return false
        val selectionStart = editor.selectionStart.takeIf { it >= 0 } ?: editable.length
        val selectionEnd = editor.selectionEnd.takeIf { it >= 0 } ?: editable.length
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        editable.replace(start, end, text)
        val nextCursor = (start + text.length).coerceAtMost(editable.length)
        editor.setSelection(nextCursor)
        if (editor === addReadingEdit) {
            saveWordButton?.isEnabled = editable.isNotBlank()
        }
        return true
    }

    private fun deleteFromAddWordEditor(): Boolean {
        val editor = currentAddWordEditor() ?: return false
        val editable = editor.text ?: return false
        if (editable.isEmpty()) return true

        val selectionStart = editor.selectionStart.takeIf { it >= 0 } ?: editable.length
        val selectionEnd = editor.selectionEnd.takeIf { it >= 0 } ?: editable.length
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)

        if (start != end) {
            editable.delete(start, end)
            editor.setSelection(start.coerceAtMost(editable.length))
        } else if (start > 0) {
            editable.delete(start - 1, start)
            editor.setSelection((start - 1).coerceAtMost(editable.length))
        }
        if (editor === addReadingEdit) {
            saveWordButton?.isEnabled = editable.isNotBlank()
        }
        return true
    }

    private fun moveCursorInAddWordEditor(delta: Int): Boolean {
        val editor = currentAddWordEditor() ?: return false
        val textLength = editor.text?.length ?: 0
        val selection = editor.selectionStart.takeIf { it >= 0 } ?: textLength
        val next = (selection + delta).coerceIn(0, textLength)
        editor.setSelection(next)
        return true
    }

    private fun refreshSuggestions() {
        refreshClipboardButtonState()
        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        val pkg = currentPackageName()
        val memory = appMemory.getOrPut(pkg) { AppBuffer() }

        val conversion = conversionState
        if (conversion != null) {
            if (canShowConversionCandidates(before, conversion)) {
                renderConversionCandidates(conversion)
                return
            }
            clearConversionState()
        }

        val trailingReadingRaw = extractTrailingReading(before)
        if (trailingReadingRaw.isNotBlank()) {
            val normalizedReading = normalizeReadingKana(trailingReadingRaw)
            if (normalizedReading.length >= 2) {
                val candidates = buildConversionCandidates(
                    reading = normalizedReading,
                    beforeCursor = before,
                    afterCursor = after,
                    history = memory.history.toString()
                )
                if (candidates.isNotEmpty()) {
                    renderInlineConversionCandidates(
                        sourceReading = trailingReadingRaw,
                        candidates = candidates
                    )
                    return
                }
            }
        }

        val suggestions = suggestionEngine.generate(
            beforeCursor = before,
            afterCursor = after,
            appHistory = memory.history.toString(),
            dictionary = dictionaryEntries,
            recentPhrases = memory.toRecentPhrases()
        )
        renderCandidates(suggestions)
    }

    private fun extractTrailingReading(beforeCursor: String): String {
        return Regex("([ぁ-ゖァ-ヺー]{2,})$")
            .find(beforeCursor.takeLast(80))
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun renderCandidates(suggestions: List<String>) {
        val container = candidateContainer ?: return
        container.removeAllViews()
        val chipHeight = candidateChipHeightPx.takeIf { it > 0 } ?: dp(40)
        val chipMinWidth = candidateChipMinWidthPx.takeIf { it > 0 } ?: dp(84)
        val chipTextSize = candidateChipTextSizeSp

        suggestions.take(suggestionDisplayCount).forEach { suggestion ->
            val button = Button(this).apply {
                text = suggestion
                textSize = chipTextSize
                isAllCaps = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minWidth = chipMinWidth
                setPadding(dp(10), dp(0), dp(10), dp(0))
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    lastSelectedCandidate = suggestion
                    commitAndTrack(suggestion)
                    setStatus("候補を挿入")
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                chipHeight
            )
            params.marginEnd = dp(6)
            container.addView(button, params)
        }
    }

    private fun renderInlineConversionCandidates(sourceReading: String, candidates: List<String>) {
        val container = candidateContainer ?: return
        container.removeAllViews()
        candidateScroll?.scrollTo(0, 0)

        val chipHeight = candidateChipHeightPx.takeIf { it > 0 } ?: dp(40)
        val chipMinWidth = candidateChipMinWidthPx.takeIf { it > 0 } ?: dp(84)
        val chipTextSize = candidateChipTextSizeSp

        val guide = TextView(this).apply {
            text = "変換候補"
            textSize = (chipTextSize - 0.5f).coerceAtLeast(10f)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = chipMinWidth
            setTextColor(0xFFE2E8F0.toInt())
            setBackgroundResource(R.drawable.bg_flick_action_key)
        }
        container.addView(
            guide,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                chipHeight
            ).apply {
                marginEnd = dp(6)
            }
        )

        candidates.take(maxInlineConversionDisplayCount).forEachIndexed { index, candidate ->
            val label = "${index + 1} ${candidate.trim()}"
            val button = Button(this).apply {
                text = label
                textSize = chipTextSize
                isAllCaps = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minWidth = chipMinWidth
                setPadding(dp(10), dp(0), dp(10), dp(0))
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    if (applyInlineConversionCandidate(sourceReading, candidate)) {
                        setStatus("変換候補を挿入")
                        refreshSuggestions()
                    } else {
                        setStatus("変換位置が変わったため候補を再計算")
                        refreshSuggestions()
                    }
                }
            }
            container.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    chipHeight
                ).apply {
                    marginEnd = dp(6)
                }
            )
        }
    }

    private fun applyInlineConversionCandidate(sourceReading: String, candidate: String): Boolean {
        if (sourceReading.isBlank()) return false
        val target = candidate.trim()
        if (target.isBlank()) return false

        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(sourceReading.length, 0)?.toString().orEmpty()
        if (!before.endsWith(sourceReading)) {
            return false
        }
        ic.deleteSurroundingText(sourceReading.length, 0)
        ic.commitText(target, 1)
        rememberCommittedText(target)
        lastSelectedCandidate = target
        clearToggleTapState()
        clearConversionState()
        return true
    }

    private fun renderConversionCandidates(state: ConversionState) {
        val container = candidateContainer ?: return
        container.removeAllViews()
        candidateScroll?.scrollTo(0, 0)

        val chipHeight = candidateChipHeightPx.takeIf { it > 0 } ?: dp(40)
        val chipMinWidth = candidateChipMinWidthPx.takeIf { it > 0 } ?: dp(84)
        val chipTextSize = (candidateChipTextSizeSp - 0.5f).coerceAtLeast(11f)

        val guide = TextView(this).apply {
            text = "変換候補"
            textSize = (chipTextSize - 0.5f).coerceAtLeast(10f)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minWidth = chipMinWidth
            setTextColor(0xFFE2E8F0.toInt())
            setBackgroundResource(R.drawable.bg_flick_action_key)
        }
        container.addView(
            guide,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                chipHeight
            ).apply {
                marginEnd = dp(6)
            }
        )

        state.candidates.take(maxConversionDisplayCount).forEachIndexed { index, candidate ->
            val label = "${index + 1} ${candidate.trim()}"
            val isSelected = index == state.index
            val button = Button(this).apply {
                text = label
                textSize = chipTextSize
                isAllCaps = false
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minWidth = chipMinWidth
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_chip_active else R.drawable.bg_chip
                )
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    if (applyConversionCandidateSelection(index)) {
                        refreshSuggestions()
                    } else {
                        clearConversionState()
                        refreshSuggestions()
                    }
                }
            }
            container.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    chipHeight
                ).apply {
                    marginEnd = dp(6)
                }
            )
        }
    }

    private fun commitAndTrack(text: String) {
        if (text.isEmpty()) return
        clearToggleTapState()
        clearConversionState()
        currentInputConnection?.commitText(text, 1)

        rememberCommittedText(text)
        refreshSuggestions()
    }

    private fun refreshClipboardButtonState() {
        val button = clipboardPasteButton ?: return
        val hasClip = getClipboardText().isNotBlank()
        button.text = "貼付"
        button.isEnabled = hasClip
        button.alpha = if (hasClip) 1f else 0.55f
    }

    private fun getClipboardText(): String {
        val manager = clipboardManager ?: getSystemService(ClipboardManager::class.java)?.also {
            clipboardManager = it
        } ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) return ""
        return text.trim().take(maxClipboardInsertChars)
    }

    private fun getCurrentBeforeCursor(): String {
        return getTextBeforeCursor(contextWindowSize)
    }

    private fun getCurrentAfterCursor(): String {
        return getTextAfterCursor(contextWindowSize)
    }

    private fun currentPackageName(): String {
        return currentInputEditorInfo?.packageName ?: "unknown"
    }

    private fun showInputMethodPickerSafely() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showInputMethodPicker()
    }

    private fun convertReadingToCandidate() {
        if (currentAddWordEditor() != null) {
            setStatus("単語追加中は入力欄を編集してください")
            return
        }
        if (!localDictionary.isLoaded()) {
            serviceScope.launch {
                runCatching { localDictionary.loadIfNeeded() }
                    .onFailure {
                        setStatus("辞書の読み込みに失敗しました")
                    }
                    .onSuccess {
                        setStatus("辞書を読み込みました。もう一度変換してください")
                    }
            }
            setStatus("辞書を読み込み中です...")
            return
        }
        val ic = currentInputConnection ?: return
        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        val pkg = currentPackageName()
        val history = appMemory[pkg]?.history?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val existing = conversionState

        if (
            existing != null &&
            now - existing.timestampMs <= convertCycleWindowMs &&
            existing.candidates.size > 1 &&
            canShowConversionCandidates(before, existing)
        ) {
            val nextIndex = (existing.index + 1) % existing.candidates.size
            if (applyConversionCandidateSelection(nextIndex)) {
                refreshSuggestions()
                return
            }
        }

        val sourceReading = Regex("([ぁ-ゖァ-ヺー]+)$")
            .find(before.takeLast(60))
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val reading = normalizeReadingKana(sourceReading)
        if (reading.isBlank()) {
            setStatus("かなを入力してから変換してください")
            return
        }

        val candidates = buildConversionCandidates(
            reading = reading,
            beforeCursor = before,
            afterCursor = after,
            history = history
        )
        if (candidates.isEmpty()) {
            val fallback = convertSurfaceText(
                input = sourceReading,
                beforeCursor = before,
                afterCursor = after,
                history = history,
                allowLocalDictionary = true
            ).trim()
            if (fallback.isNotBlank() && fallback != sourceReading) {
                ic.deleteSurroundingText(sourceReading.length, 0)
                ic.commitText(fallback, 1)
                rememberCommittedText(fallback)
                clearConversionState()
                lastSelectedCandidate = fallback
                setStatus("文脈に合わせて変換しました")
                refreshSuggestions()
                return
            }
            setStatus("変換候補なし: 単語帳に登録してください")
            return
        }

        val first = candidates.first()
        ic.deleteSurroundingText(sourceReading.length, 0)
        ic.commitText(first, 1)
        rememberCommittedText(first)
        val newState = ConversionState(
            reading = reading,
            candidates = candidates,
            index = 0,
            currentOutput = first,
            timestampMs = now
        )
        conversionState = newState
        lastSelectedCandidate = first
        setStatus(buildConversionCandidatePreview(newState))
        refreshSuggestions()
    }

    private fun canShowConversionCandidates(beforeCursor: String, state: ConversionState): Boolean {
        if (state.currentOutput.isBlank()) return false
        if (beforeCursor.isBlank()) {
            return System.currentTimeMillis() - state.timestampMs <= convertFallbackWindowMs
        }
        return beforeCursor.endsWith(state.currentOutput)
    }

    private fun applyConversionCandidateSelection(targetIndex: Int): Boolean {
        val state = conversionState ?: return false
        val target = state.candidates.getOrNull(targetIndex)?.trim().orEmpty()
        if (target.isBlank()) return false

        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(state.currentOutput.length, 0)?.toString().orEmpty()
        val allowBlindReplace = before.isBlank() &&
            System.currentTimeMillis() - state.timestampMs <= convertFallbackWindowMs
        if (!before.endsWith(state.currentOutput) && !allowBlindReplace) {
            return false
        }

        ic.deleteSurroundingText(state.currentOutput.length, 0)
        ic.commitText(target, 1)
        rememberCommittedText(target)
        lastSelectedCandidate = target
        conversionState = state.copy(
            index = targetIndex,
            currentOutput = target,
            timestampMs = System.currentTimeMillis()
        )
        setStatus(buildConversionCandidatePreview(conversionState ?: state))
        return true
    }

    private fun buildConversionCandidatePreview(state: ConversionState): String {
        val top = state.candidates.take(6).joinToString(" / ") { it.trim() }
        return "変換候補 ${state.index + 1}/${state.candidates.size}: $top"
    }

    private fun buildConversionCandidates(
        reading: String,
        beforeCursor: String,
        afterCursor: String,
        history: String
    ): List<String> {
        val normalizedReading = normalizeReadingKana(reading)
        if (normalizedReading.isBlank()) return emptyList()

        val readingVariants = buildReadingVariantsForSearch(normalizedReading)
        if (readingVariants.isEmpty()) return emptyList()

        val rules = buildConversionRules()
        val merged = linkedSetOf<String>()
        val preferredOrder = mutableListOf<String>()
        val variantBonus = mutableMapOf<String, Int>()

        readingVariants.forEach { variant ->
            val suffix = variant.suffix
            val variantCandidates = mutableListOf<String>()

            dictionaryEntries
                .asSequence()
                .mapNotNull { entry ->
                    val entryReading = normalizeReadingKana(entry.readingKana)
                    if (entryReading == variant.baseReading) entry else null
                }
                .sortedWith(
                    compareByDescending<DictionaryEntry> { it.priority }
                        .thenByDescending { it.createdAt }
                )
                .map { it.word.trim() }
                .filter { it.isNotBlank() }
                .forEach { word ->
                    appendSuffixCandidate(word, suffix)?.let { variantCandidates.add(it) }
                }

            builtInConversionCandidates[variant.baseReading]
                .orEmpty()
                .forEach { word ->
                    appendSuffixCandidate(word.trim(), suffix)?.let { variantCandidates.add(it) }
                }

            localDictionary
                .candidatesFor(variant.baseReading, limit = conversionLocalCandidateLimit)
                .forEach { word ->
                    appendSuffixCandidate(word.trim(), suffix)?.let { variantCandidates.add(it) }
                }

            val greedy = applyLongestRuleConversion(variant.baseReading, rules)
            if (greedy.replacedCount > 0 && greedy.output.isNotBlank()) {
                appendSuffixCandidate(greedy.output.trim(), suffix)?.let { variantCandidates.add(it) }
            }

            val katakanaBase = hiraganaToKatakana(variant.baseReading)
            if (katakanaBase.isNotBlank() && katakanaBase != variant.baseReading) {
                appendSuffixCandidate(katakanaBase, suffix)?.let { variantCandidates.add(it) }
            }

            val rawReadingWithSuffix = appendSuffixCandidate(variant.baseReading, suffix)
            if (!rawReadingWithSuffix.isNullOrBlank()) {
                variantCandidates.add(rawReadingWithSuffix)
            }

            variantCandidates
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { candidate ->
                    merged.add(candidate)
                    preferredOrder.add(candidate)
                    val current = variantBonus[candidate] ?: Int.MIN_VALUE
                    if (variant.baseBonus > current) {
                        variantBonus[candidate] = variant.baseBonus
                    }
            }
        }

        val composedFromLocal = buildComposedLocalCandidate(normalizedReading)
        if (!composedFromLocal.isNullOrBlank()) {
            merged.add(composedFromLocal)
            preferredOrder.add(composedFromLocal)
            val currentBonus = variantBonus[composedFromLocal] ?: Int.MIN_VALUE
            if (320 > currentBonus) {
                variantBonus[composedFromLocal] = 320
            }
        }

        if (merged.isEmpty()) return emptyList()

        return rankConversionCandidates(
            reading = normalizedReading,
            candidates = merged,
            beforeCursor = beforeCursor,
            afterCursor = afterCursor,
            history = history,
            preferredOrder = preferredOrder,
            additionalBonus = variantBonus
        ).take(maxConversionCandidates)
    }

    private fun rankConversionCandidates(
        reading: String,
        candidates: Collection<String>,
        beforeCursor: String,
        afterCursor: String,
        history: String,
        preferredOrder: List<String>,
        additionalBonus: Map<String, Int> = emptyMap()
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val preferredBonus = mutableMapOf<String, Int>()
        preferredOrder
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEachIndexed { index, candidate ->
                val bonus = (260 - index * 14).coerceAtLeast(20)
                val current = preferredBonus[candidate] ?: Int.MIN_VALUE
                if (bonus > current) {
                    preferredBonus[candidate] = bonus
                }
            }

        val contextTokens = (
            extractJapaneseTokens(beforeCursor) +
                extractJapaneseTokens(afterCursor) +
                extractJapaneseTokens(history)
            )
            .takeLast(36)
            .toSet()
        val contextText = buildString {
            append(beforeCursor.takeLast(220))
            append(' ')
            append(afterCursor.take(120))
            append(' ')
            append(history.takeLast(260))
        }
        val recentPhrases = appMemory[currentPackageName()]
            ?.toRecentPhrases()
            ?.map { it.text.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

        return candidates
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { candidate ->
                var score = scoreRecognitionCandidate(
                    candidate = candidate,
                    contextTokens = contextTokens,
                    contextText = contextText,
                    recentPhrases = recentPhrases
                )
                score += preferredBonus[candidate] ?: 0
                score += additionalBonus[candidate] ?: 0
                if (candidate == reading) {
                    // 未変換候補を極端に不利にしない。無理な漢字化より安全な出力を優先する。
                    score -= 48
                }
                if (candidate == hiraganaToKatakana(reading)) {
                    score -= 45
                }
                if (geoNameRegex.containsMatchIn(candidate)) {
                    score += if (workTravelRegex.containsMatchIn(contextText)) 22 else 8
                }
                if (personNameRegex.containsMatchIn(candidate)) {
                    score += 10
                }
                if (usageSceneMode == UsageSceneMode.WORK && workLikeRegex.containsMatchIn(candidate)) {
                    score += 12
                }
                if (usageSceneMode == UsageSceneMode.MESSAGE && messageLikeRegex.containsMatchIn(candidate)) {
                    score += 10
                }
                score += applyContextualHomophoneBoost(
                    reading = reading,
                    candidate = candidate,
                    beforeCursor = beforeCursor,
                    afterCursor = afterCursor,
                    contextText = contextText
                )
                score -= manualConvertStabilityPenalty(
                    reading = reading,
                    candidate = candidate
                )
                candidate to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()
    }

    private fun manualConvertStabilityPenalty(
        reading: String,
        candidate: String
    ): Int {
        val normalizedReading = normalizeReadingKana(reading)
        if (normalizedReading.isBlank()) return 0

        val normalizedCandidate = candidate.trim()
        if (normalizedCandidate.isBlank()) return 0
        if (normalizedCandidate == normalizedReading) return 0

        val kanjiCount = normalizedCandidate.count { it in '一'..'龯' || it == '々' }
        val hiraganaCount = normalizedCandidate.count { it in 'ぁ'..'ゖ' }
        val katakanaCount = normalizedCandidate.count { it in 'ァ'..'ヺ' || it == 'ー' }
        val kanaOnlyReading = normalizedReading.count { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' }

        var penalty = 0

        // 短い読みで全漢字化する候補は過変換になりやすい。
        if (kanaOnlyReading <= 5 && kanjiCount >= 3 && hiraganaCount == 0 && katakanaCount == 0) {
            penalty += 60
        }
        if (normalizedCandidate.length >= normalizedReading.length + 4) {
            penalty += 30
        }
        if (kanaOnlyReading <= 3 && kanjiCount >= 1 && normalizedCandidate.length >= 4) {
            penalty += 20
        }

        return penalty
    }

    private fun buildReadingVariantsForSearch(reading: String): List<ReadingVariant> {
        if (reading.isBlank()) return emptyList()
        val normalized = normalizeReadingKana(reading)
        val merged = linkedMapOf<String, ReadingVariant>()
        val queue = ArrayDeque<ReadingVariant>()
        val root = ReadingVariant(
            baseReading = normalized,
            suffix = "",
            baseBonus = 260,
            depth = 0
        )
        queue.add(root)
        merged["${root.baseReading}|${root.suffix}"] = root

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.depth >= 2) continue

            conversionTailSuffixes.forEachIndexed { index, suffix ->
                if (!current.baseReading.endsWith(suffix)) return@forEachIndexed
                val base = current.baseReading.dropLast(suffix.length)
                if (base.length < 2) return@forEachIndexed
                val variant = ReadingVariant(
                    baseReading = base,
                    suffix = suffix + current.suffix,
                    baseBonus = (current.baseBonus - 28 - index * 4).coerceAtLeast(72),
                    depth = current.depth + 1
                )
                val key = "${variant.baseReading}|${variant.suffix}"
                val existing = merged[key]
                if (existing == null || variant.baseBonus > existing.baseBonus) {
                    merged[key] = variant
                    queue.add(variant)
                }
            }
        }

        return merged.values
            .sortedByDescending { it.baseBonus }
            .toList()
    }

    private fun appendSuffixCandidate(base: String, suffix: String): String? {
        val normalizedBase = base.trim()
        if (normalizedBase.isBlank()) return null
        if (suffix.isBlank()) return normalizedBase
        if (normalizedBase.endsWith(suffix)) return normalizedBase
        return normalizedBase + suffix
    }

    private fun buildComposedLocalCandidate(reading: String): String {
        if (reading.length < 2) return ""
        val rules = buildSurfaceConversionRules(
            input = reading,
            allowLocalDictionary = true
        )
        if (rules.isEmpty()) return ""

        val greedy = applyLongestRuleConversion(reading, rules).output.trim()
        if (greedy.isBlank() || greedy == reading) return ""

        val hasKanji = greedy.any { it in '一'..'龯' || it == '々' }
        return if (hasKanji) greedy else ""
    }

    private fun applyContextualHomophoneBoost(
        reading: String,
        candidate: String,
        beforeCursor: String,
        afterCursor: String,
        contextText: String
    ): Int {
        var bonus = 0
        val merged = buildString {
            append(beforeCursor.takeLast(96))
            append(' ')
            append(afterCursor.take(64))
            append(' ')
            append(contextText.takeLast(160))
        }

        when (reading) {
            "せいど" -> {
                if (candidate.contains("精度") && precisionContextRegex.containsMatchIn(merged)) {
                    bonus += 70
                }
                if (candidate.contains("制度") && institutionContextRegex.containsMatchIn(merged)) {
                    bonus += 64
                }
                if (candidate.contains("制動") && brakingContextRegex.containsMatchIn(merged)) {
                    bonus += 58
                }
            }

            "せいさ" -> {
                if (candidate.contains("精査") && inspectContextRegex.containsMatchIn(merged)) {
                    bonus += 58
                }
                if (candidate.contains("精度") && precisionContextRegex.containsMatchIn(merged)) {
                    bonus += 42
                }
            }
        }

        if (nameHonorificContextRegex.containsMatchIn(merged) && personNameKanjiRegex.matches(candidate)) {
            bonus += 58
        }
        if (movementContextRegex.containsMatchIn(merged) && geoCandidateRegex.containsMatchIn(candidate)) {
            bonus += 52
        }
        if (candidate.length <= 1) {
            bonus -= 35
        }

        return bonus
    }

    private fun buildConversionRules(): List<ConversionRule> {
        val result = mutableListOf<ConversionRule>()

        dictionaryEntries.forEach { entry ->
            val from = normalizeReadingKana(entry.readingKana.trim())
            val to = entry.word.trim()
            if (from.isBlank() || to.isBlank()) return@forEach
            val score = 900 + entry.priority * 25
            result.add(
                ConversionRule(
                    reading = from,
                    output = to,
                    score = score
                )
            )
        }

        builtInConversionCandidates.forEach { (from, outputs) ->
            if (outputs.isEmpty()) return@forEach
            val main = outputs.first().trim()
            if (main.isBlank()) return@forEach
            result.add(
                ConversionRule(
                    reading = from,
                    output = main,
                    score = 300 + from.length * 5
                )
            )
        }

        return result
            .sortedWith(
                compareByDescending<ConversionRule> { it.reading.length }
                    .thenByDescending { it.score }
            )
    }

    private fun applyLongestRuleConversion(input: String, rules: List<ConversionRule>): ConversionResult {
        if (input.isBlank() || rules.isEmpty()) {
            return ConversionResult(output = input, replacedCount = 0)
        }

        val out = StringBuilder()
        var index = 0
        var replacedCount = 0

        while (index < input.length) {
            val matched = rules.firstOrNull { rule ->
                index + rule.reading.length <= input.length &&
                    input.regionMatches(index, rule.reading, 0, rule.reading.length)
            }

            if (matched != null) {
                out.append(matched.output)
                if (matched.output != matched.reading) {
                    replacedCount += 1
                }
                index += matched.reading.length
            } else {
                out.append(input[index])
                index += 1
            }
        }

        return ConversionResult(
            output = out.toString(),
            replacedCount = replacedCount
        )
    }

    private fun normalizeReadingKana(input: String): String {
        if (input.isBlank()) return ""
        val out = StringBuilder(input.length)
        input.forEach { char ->
            when {
                char in 'ァ'..'ヶ' -> out.append((char.code - 0x60).toChar())
                char == 'ヴ' -> out.append('ゔ')
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun hiraganaToKatakana(input: String): String {
        if (input.isBlank()) return ""
        val out = StringBuilder(input.length)
        input.forEach { char ->
            if (char in 'ぁ'..'ゖ') {
                out.append((char.code + 0x60).toChar())
            } else {
                out.append(char)
            }
        }
        return out.toString()
    }

    private fun buildAlphaFlickMap(): Map<String, Array<String>> {
        if (!alphaUppercase) return alphaBaseFlickMap
        return alphaBaseFlickMap.mapValues { (_, values) ->
            Array(values.size) { index ->
                values[index].map { ch ->
                    if (ch in 'a'..'z') ch.uppercaseChar() else ch
                }.joinToString("")
            }
        }
    }

    private fun rememberCommittedText(text: String) {
        if (text.isBlank()) return
        lastCommittedText = text
        val pkg = currentPackageName()
        val memory = appMemory.getOrPut(pkg) { AppBuffer() }
        memory.appendHistory(text)
        if (text.length >= 2 && !text.contains('\n')) {
            memory.addRecentPhrase(text)
        }
    }

    private fun selectBestRecognitionCandidate(alternatives: List<String>): String {
        if (alternatives.isEmpty()) return ""

        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        val pkg = currentPackageName()
        val history = appMemory[pkg]?.history?.toString().orEmpty()
        val scored = rankRecognitionCandidates(
            alternatives = alternatives,
            before = before,
            after = after,
            history = history
        )

        return scored.firstOrNull()?.corrected.orEmpty()
    }

    private fun rankRecognitionCandidates(
        alternatives: List<String>,
        before: String,
        after: String,
        history: String
    ): List<RecognizedCandidate> {
        val contextTokens = (
            extractJapaneseTokens(before) +
                extractJapaneseTokens(after) +
                extractJapaneseTokens(history)
            )
            .takeLast(36)
            .toSet()
        val contextText = buildString {
            append(before.takeLast(200))
            append(' ')
            append(after.take(120))
            append(' ')
            append(history.takeLast(260))
        }
        val recentPhrases = appMemory[currentPackageName()]
            ?.toRecentPhrases()
            ?.map { it.text.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

        return alternatives
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { raw ->
                val corrected = applyDictionaryCorrections(raw)
                val score = scoreRecognitionCandidate(
                    candidate = corrected,
                    contextTokens = contextTokens,
                    contextText = contextText,
                    recentPhrases = recentPhrases
                )
                RecognizedCandidate(
                    original = raw,
                    corrected = corrected,
                    score = score
                )
            }
            .groupBy { it.corrected }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
    }

    private fun scoreRecognitionCandidate(
        candidate: String,
        contextTokens: Set<String>,
        contextText: String,
        recentPhrases: Set<String>
    ): Int {
        if (candidate.isBlank()) return Int.MIN_VALUE
        var score = 0

        score += candidate.length.coerceAtMost(40)

        val dictionaryWordHits = dictionaryEntries.count { entry ->
            val word = entry.word.trim()
            word.isNotBlank() && candidate.contains(word)
        }
        score += dictionaryWordHits * 45

        val readingHits = dictionaryEntries.count { entry ->
            val reading = entry.readingKana.trim()
            reading.isNotBlank() && normalizeReadingKana(candidate).contains(normalizeReadingKana(reading))
        }
        score += readingHits * 20

        contextTokens.forEach { token ->
            if (token.length >= 2 && candidate.contains(token)) {
                score += 24
            }
        }

        if (contextText.contains(candidate)) {
            score += 36
        }

        if (recentPhrases.any { phrase -> phrase.isNotBlank() && candidate.contains(phrase) }) {
            score += 28
        }

        val bigrams = buildBigrams(candidate)
        var bigramMatches = 0
        bigrams.forEach { bg ->
            if (bg.length >= 2 && contextText.contains(bg)) {
                bigramMatches += 1
            }
        }
        score += (bigramMatches * 6).coerceAtMost(48)

        contextTokens
            .asSequence()
            .map { normalizeReadingKana(it) }
            .distinct()
            .forEach { reading ->
                localDictionary.candidatesFor(reading, limit = 2).forEach { localWord ->
                    if (localWord.length >= 2 && candidate.contains(localWord)) {
                        score += 18
                    }
                }
            }

        // 「漢字が多い候補を優遇」すると過変換になりやすいため、ここでは表記種別で加点しない。

        val mergedContext = "$contextText ${recentPhrases.joinToString(" ")}"
        if (usageSceneMode == UsageSceneMode.WORK) {
            if (workLikeRegex.containsMatchIn(candidate)) {
                score += 24
            }
            if (casualLikeRegex.containsMatchIn(candidate) && !workLikeRegex.containsMatchIn(candidate)) {
                score -= 8
            }
            if (geoNameRegex.containsMatchIn(candidate) && workTravelRegex.containsMatchIn(mergedContext)) {
                score += 18
            }
        } else {
            if (messageLikeRegex.containsMatchIn(candidate)) {
                score += 16
            }
            if (casualLikeRegex.containsMatchIn(candidate)) {
                score += 10
            }
        }

        return score
    }

    private fun refineFormatSourceWithRecognitionCandidates(
        source: String,
        before: String,
        after: String,
        history: String
    ): String {
        if (source.isBlank()) return source
        if (source != lastVoiceCommittedText) return source
        if (lastRecognitionAlternatives.isEmpty()) return source

        val ranked = rankRecognitionCandidates(
            alternatives = (lastRecognitionAlternatives + source).distinct(),
            before = before,
            after = after,
            history = history
        )
        if (ranked.isEmpty()) return source

        val currentScore = ranked.firstOrNull { it.corrected == source }?.score ?: Int.MIN_VALUE
        val top = ranked.first()
        if (top.corrected == source) return source
        if (top.score < currentScore + formatRerankMinGain) return source
        return top.corrected
    }

    private fun buildBigrams(text: String): Set<String> {
        if (text.length < 2) return emptySet()
        val result = linkedSetOf<String>()
        for (i in 0 until text.length - 1) {
            result.add(text.substring(i, i + 2))
        }
        return result
    }

    private fun extractJapaneseTokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return tokenRegex.findAll(text)
            .map { it.value }
            .filter { it.length in 2..16 }
            .take(32)
            .toList()
    }

    private fun applyDictionaryCorrections(input: String): String {
        if (input.isBlank() || dictionaryEntries.isEmpty()) {
            return input
        }

        val normalizedInput = normalizeReadingKana(input)
        val exact = dictionaryEntries.firstOrNull { normalizeReadingKana(it.readingKana) == normalizedInput }
        if (exact != null) {
            return exact.word
        }

        val rules = dictionaryEntries
            .mapNotNull { entry ->
                val from = normalizeReadingKana(entry.readingKana.trim())
                val to = entry.word.trim()
                if (from.isBlank() || to.isBlank() || from.length < 2) {
                    null
                } else {
                    from to to
                }
            }
            .sortedByDescending { it.first.length }

        if (rules.isEmpty()) {
            return input
        }

        val out = StringBuilder()
        var index = 0
        val normalizedText = normalizeReadingKana(input)
        while (index < input.length) {
            val matched = rules.firstOrNull { (from, to) ->
                index + from.length <= input.length &&
                    normalizedText.regionMatches(index, from, 0, from.length) &&
                    shouldApplyUserDictionaryRule(
                        text = input,
                        start = index,
                        len = from.length,
                        replacement = to
                    )
            }

            if (matched != null) {
                out.append(matched.second)
                index += matched.first.length
            } else {
                out.append(input[index])
                index += 1
            }
        }
        return out.toString()
    }

    private fun shouldApplyUserDictionaryRule(
        text: String,
        start: Int,
        len: Int,
        replacement: String
    ): Boolean {
        if (hasWordBoundary(text, start, len)) {
            return true
        }

        // 長文中の人名・固有名詞（例: まゆちゃん / まゆは）を拾いやすくする。
        if (replacement.none { ch -> ch in '一'..'龯' || ch in 'ァ'..'ヺ' || ch in 'A'..'Z' || ch in 'a'..'z' }) {
            return false
        }

        val leftChar = if (start > 0) text[start - 1] else null
        val rightIndex = start + len
        val rightChar = if (rightIndex < text.length) text[rightIndex] else null
        val rightSlice = if (rightIndex < text.length) text.substring(rightIndex) else ""

        val leftLooseBoundary = leftChar == null ||
            !isJapaneseWordChar(leftChar) ||
            leftChar in looseBoundaryChars
        val rightLooseBoundary = rightChar == null ||
            !isJapaneseWordChar(rightChar) ||
            rightChar in looseBoundaryChars ||
            honorificOrParticleSuffixes.any { suffix -> rightSlice.startsWith(suffix) }

        return leftLooseBoundary && rightLooseBoundary
    }

    private fun hasWordBoundary(text: String, start: Int, len: Int): Boolean {
        if (text.length == len) {
            return true
        }
        val leftOk = start == 0 || !isJapaneseWordChar(text[start - 1])
        val rightIndex = start + len
        val rightOk = rightIndex >= text.length || !isJapaneseWordChar(text[rightIndex])
        return leftOk && rightOk
    }

    private fun isJapaneseWordChar(char: Char): Boolean {
        if (char.isLetterOrDigit()) {
            return true
        }
        return when (Character.UnicodeBlock.of(char)) {
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS -> true

            else -> false
        }
    }

    private fun applyImeSizingPolicy() {
        val root = rootView ?: return
        val (windowWidth, windowHeight) = resolveUsableWindowSize()
        if (windowWidth <= 0 || windowHeight <= 0) return

        val configuration = resources.configuration
        val sw = configuration.smallestScreenWidthDp
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 基準計算にユーザー調整倍率を掛けて最終高さを決定する。
        val baseTargetImeHeight = (windowHeight * when {
            sw >= 600 && isLandscape -> 0.42f
            sw >= 600 -> 0.36f
            isLandscape -> 0.50f
            else -> 0.44f
        }).roundToInt()
        val targetImeHeight = (baseTargetImeHeight * runtimeImeHeightScale)
            .roundToInt()
            .coerceIn((windowHeight * 0.30f).roundToInt(), (windowHeight * 0.72f).roundToInt())

        val guideTextSizeSp = if (sw >= 600) 14f else if (isLandscape) 10.5f else 11f
        val statusTextSizeSp = if (sw >= 600) 15f else if (isLandscape) 11.5f else 12f
        val flickTextSizeSp = if (sw >= 600) 18f else if (isLandscape) 14f else 16f
        flickDeadZonePx = when {
            sw >= 700 -> dp(18)
            sw >= 600 -> dp(16)
            isLandscape -> dp(15)
            else -> dp(13)
        }
        
        candidateChipTextSizeSp = if (sw >= 600) 16f else if (isLandscape) 13f else 15f
        flickKeyTextSizeSp = flickTextSizeSp

        // 画面サイズに合わせてキー高をクランプし、Fold展開時の過大表示を抑える。
        statusText?.textSize = statusTextSizeSp
        if (flickGuideText?.isVisible == true) {
            flickGuideText?.textSize = guideTextSizeSp
        }

        resizeTenKeyButtons(root, targetImeHeight)
        // テンキーのスタイリング（文字サイズ・ラベル）
        styleFlickTenKeys(root)
    }

    private fun resizeTenKeyButtons(root: View, heightPx: Int) {
        val keyHeight = (heightPx * 0.16f).roundToInt().coerceIn(dp(62), dp(78))
        val micRowHeight = (keyHeight + dp(6)).coerceIn(dp(64), dp(88))
        val candidateHeight = (keyHeight - dp(8)).coerceIn(dp(46), dp(62))
        val statusHeight = dp(24)
        val heightControlRowHeight = (keyHeight - dp(30)).coerceIn(dp(30), dp(40))

        fun apply(view: View) {
            when {
                view.id == R.id.micButton ||
                    view.id == R.id.formatContextButton -> {
                    updateViewHeight(view, micRowHeight)
                }
                view.id == R.id.statusText -> {
                    if (statusText?.isVisible == true) {
                        updateViewHeight(view, statusHeight)
                    }
                }
                view.id == R.id.imeHeightControlRow -> {
                    updateViewHeight(view, heightControlRowHeight)
                }
                view.id == R.id.clipboardPasteButton ||
                    view.id == R.id.addWordButton -> {
                    updateViewHeight(view, candidateHeight)
                }
                view.id == R.id.candidateRow -> {
                    updateViewHeight(view, candidateHeight + dp(4))
                }
                view is Button -> {
                    val tag = view.tag as? String
                    if (!tag.isNullOrBlank() && isTenKeyTag(tag)) {
                        updateViewHeight(view, keyHeight)
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    apply(view.getChildAt(index))
                }
            }
        }

        apply(root)
    }

    private fun isTenKeyTag(tag: String): Boolean {
        if (tag.startsWith("F")) return true
        return tag in setOf(
            "CONVERT",
            "BACKSPACE",
            "CURSOR_LEFT",
            "CURSOR_RIGHT",
            "MODE_CYCLE",
            "LANG_PICKER",
            "SPACE",
            "ENTER",
            "SMALL",
            "DAKUTEN",
            "HANDAKUTEN"
        )
    }

    private fun updateViewHeight(view: View?, targetHeightPx: Int) {
        val target = view ?: return
        val params = target.layoutParams ?: return
        if (params.height == targetHeightPx) return
        params.height = targetHeightPx
        target.layoutParams = params
    }

    private fun resolveUsableWindowSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WindowManager::class.java)
            val metrics = wm?.currentWindowMetrics
            if (metrics == null) {
                val dm = resources.displayMetrics
                return dm.widthPixels to dm.heightPixels
            }
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val width = metrics.bounds.width() - insets.left - insets.right
            val height = metrics.bounds.height() - insets.top - insets.bottom
            if (width > 0 && height > 0) {
                return width to height
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun SpannableString.applySpan(span: Any, range: SpanRange) {
        setSpan(span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private data class SpanRange(
        val start: Int,
        val end: Int
    )

    private data class FlickLabelParts(
        val text: String,
        val up: SpanRange,
        val left: SpanRange,
        val center: SpanRange,
        val right: SpanRange,
        val down: SpanRange
    )

    private data class ToggleTapState(
        val tag: String,
        val index: Int,
        val timestampMs: Long,
        val output: String
    )

    private data class ConversionState(
        val reading: String,
        val candidates: List<String>,
        val index: Int,
        val currentOutput: String,
        val timestampMs: Long
    )

    private data class ReadingVariant(
        val baseReading: String,
        val suffix: String,
        val baseBonus: Int,
        val depth: Int
    )

    private data class ConversionRule(
        val reading: String,
        val output: String,
        val score: Int
    )

    private data class ConversionResult(
        val output: String,
        val replacedCount: Int
    )

    private data class RecognizedCandidate(
        val original: String,
        val corrected: String,
        val score: Int
    )

    private data class LocalFormatResult(
        val text: String,
        val note: String
    )

    private data class EnglishTranslationDecision(
        val text: String,
        val source: EnglishTranslationSource,
        val llmScore: Int,
        val fallbackScore: Int,
        val onDeviceScore: Int
    )

    private enum class EnglishTranslationSource {
        ON_DEVICE,
        LLM,
        FALLBACK,
        FALLBACK_RECOVERY,
        SOURCE
    }

    private data class PhraseStats(
        var frequency: Int = 1,
        var lastUsedAt: Long = System.currentTimeMillis()
    )

    private class AppBuffer {
        val history: StringBuilder = StringBuilder()
        val phraseMap: LinkedHashMap<String, PhraseStats> = LinkedHashMap()

        fun appendHistory(text: String) {
            if (text.isBlank()) return
            history.append(text)
            history.append(' ')

            val overflow = history.length - 500
            if (overflow > 0) {
                history.delete(0, overflow)
            }
        }

        fun addRecentPhrase(phrase: String) {
            if (phrase.isBlank()) return

            val now = System.currentTimeMillis()
            val current = phraseMap[phrase]
            if (current == null) {
                phraseMap[phrase] = PhraseStats(frequency = 1, lastUsedAt = now)
            } else {
                current.frequency += 1
                current.lastUsedAt = now
            }

            if (phraseMap.size > 60) {
                val removeKey = phraseMap.entries.minByOrNull { it.value.lastUsedAt }?.key
                if (removeKey != null) {
                    phraseMap.remove(removeKey)
                }
            }
        }

        fun toRecentPhrases(): List<RecentPhrase> {
            return phraseMap.entries.map { (text, stats) ->
                RecentPhrase(
                    text = text,
                    frequency = stats.frequency,
                    lastUsedAt = stats.lastUsedAt
                )
            }
        }
    }

    private enum class FlickDirection {
        CENTER, UP, RIGHT, DOWN, LEFT
    }

    private enum class InputMode {
        KANA,
        ALPHA,
        NUMERIC
    }

    private enum class FormatAction(val id: Int) {
        CONTEXT(1),
        ENGLISH(2);

        companion object {
            fun fromId(id: Int): FormatAction? {
                return values().firstOrNull { it.id == id }
            }
        }
    }

    private enum class AddWordField {
        WORD,
        READING
    }

    private companion object {
        const val logTag = "ToraVoiceIme"
        const val backspaceRepeatInitialDelayMs = 240L
        const val backspaceRepeatIntervalMs = 58L
        const val toggleTapWindowMs = 850L
        const val formatSlowStatusDelayMs = 2800L
        const val convertCycleWindowMs = 2200L
        const val convertFallbackWindowMs = 10000L
        const val localLlmTimeoutMs = 1800L
        const val localLlmEnglishTimeoutMs = 4500L
        const val onDeviceEnglishTimeoutMs = 3200L
        const val onDeviceEnglishPrewarmTimeoutMs = 18000L
        const val localLlmRefineTotalBudgetMs = 2600L
        const val localLlmRefineMinRemainingMs = 240L
        const val localLlmEnglishTotalBudgetMs = 5200L
        const val localLlmEnglishMinRemainingMs = 280L
        const val formatRerankMinGain = 18
        const val suggestionDisplayCount = 8
        const val maxConversionCandidates = 18
        const val maxConversionDisplayCount = 14
        const val maxInlineConversionDisplayCount = 12
        const val contextWindowSize = 200
        // 変/英 の対象抽出は長文対応のため大きめに確保（変換キー操作時のみ使用）
        const val formatActionBeforeCursorMaxChars = 6500
        const val formatActionAfterCursorMaxChars = 1200
        const val formatSourceTailMaxChars = 7000
        const val formatSourceMaxChars = 6500
        const val localLlmEnglishChunkChars = 620
        const val localLlmEnglishChunkRetryCount = 2
        const val localLlmEnglishRetryDelayMs = 160L
        const val localLlmEnglishMaxSplitDepth = 2
        const val localLlmEnglishSplitThresholdChars = 340
        const val localLlmEnglishMinChunkChars = 180
        const val localLlmFallbackMaxJapaneseRatio = 0.14f
        const val localLlmFallbackMinLatinRatio = 0.58f
        const val localLlmRefineChunkChars = 900
        const val localLlmRefineSkipInputChars = 520
        const val englishLlmMaxScoreDeficit = 6
        const val englishScoreMinLatinRatio = 0.24f
        const val englishScoreMaxJapaneseRatio = 0.18f
        const val englishRecoveryMinLatinRatio = 0.20f
        const val conversionLocalCandidateLimit = 24
        const val surfaceLocalLookupLimit = 480
        const val surfaceLocalLookupMaxReadingLength = 14
        const val surfaceLocalCandidatePerReading = 4
        const val recordingNotificationId = 42001
        const val recordingNotificationChannelId = "toraonsei_recording_state"
        const val maxClipboardPreviewChars = 18
        const val maxClipboardInsertChars = 1000
        val tokenRegex = Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}A-Za-z0-9]{1,16}")
        val surfaceKanaChunkRegex = Regex("[ぁ-ゖァ-ヺー]{2,80}")
        val workLikeRegex = Regex("(確認|対応|納品|依頼|報告|共有|会議|資料|至急|業務|連絡)")
        val workTravelRegex = Regex("(配送|配達|納品|現場|出発|到着|向かう|トラック|運行|ルート)")
        val messageLikeRegex = Regex("(笑|わら|w|ありがとう|よろしく|ごめん|了解|あとで)")
        val casualLikeRegex = Regex("(だよ|だね|かな|わら|笑|w|よろしく|ごめん)")
        val geoNameRegex = Regex("(都|道|府|県|市|区|町|村|駅|港|IC|PA|SA|JCT)")
        val personNameRegex = Regex("(さん|ちゃん|くん|君|様|氏|太郎|花子|麻祐|まゆ|弘太|なーちゃん)")
        val precisionContextRegex = Regex("(精度|認識|誤変換|命中|正確|改善|向上|品質|計測|測定|変換)")
        val institutionContextRegex = Regex("(制度|仕組|法|法律|規則|運用|改正|導入|税|保険|国|会社)")
        val brakingContextRegex = Regex("(車|トラック|ブレーキ|制動|停止|減速|運転|走行)")
        val inspectContextRegex = Regex("(確認|点検|レビュー|見直|調査|検証|精査)")
        val englishMetaRegex = Regex("(?i)\\b(translation|translated|output|result|input)\\b|<\\/?input>|<\\/?output>")
        val englishRepeatedWordRegex = Regex("(?i)\\b([a-z][a-z']{1,})\\b(?:\\s+\\1\\b){2,}")
        val englishNumberTokenRegex = Regex("\\d+[\\d:\\-./]*")
        val englishRequestSourceRegex = Regex("(教えて|ください|お願い|お願いします|してほしい|して下さい)")
        val englishPleaseRegex = Regex("(?i)\\b(please|could you|would you|can you)\\b")
        val nameHonorificContextRegex = Regex("(さん|ちゃん|くん|君|様|氏|先輩|先生)")
        val movementContextRegex = Regex("(行く|いく|来る|くる|向か|到着|出発|経由|寄る|近く|方面|まで|から|で)")
        val geoCandidateRegex = Regex("(都|道|府|県|市|区|町|村|駅|港|IC|PA|SA|JCT|タワー|ビル)")
        val personNameKanjiRegex = Regex("[一-龯々]{2,6}")
        val conversionTailSuffixes = listOf(
            "ちゃん", "さん", "くん", "さま",
            "でした", "です", "ます", "たい",
            "って", "から", "まで",
            "は", "が", "を", "に", "へ", "で", "と", "も", "の", "ね", "よ", "か", "な", "だ"
        )

        val kanaFlickMap = mapOf(
            "F1" to arrayOf("あ", "い", "う", "え", "お"),
            "F2" to arrayOf("か", "き", "く", "け", "こ"),
            "F3" to arrayOf("さ", "し", "す", "せ", "そ"),
            "F4" to arrayOf("た", "ち", "つ", "て", "と"),
            "F5" to arrayOf("な", "に", "ぬ", "ね", "の"),
            "F6" to arrayOf("は", "ひ", "ふ", "へ", "ほ"),
            "F7" to arrayOf("ま", "み", "む", "め", "も"),
            "F8" to arrayOf("や", "（", "ゆ", "）", "よ"),
            "F9" to arrayOf("ら", "り", "る", "れ", "ろ"),
            "F0" to arrayOf("わ", "を", "ん", "ー", "〜"),
            "FS" to arrayOf("、", "。", "？", "！", "…")
        )

        val alphaBaseFlickMap = mapOf(
            "F1" to arrayOf("@", "/", ":", "~", "_"),
            "F2" to arrayOf("a", "b", "c", "2", "A"),
            "F3" to arrayOf("d", "e", "f", "3", "D"),
            "F4" to arrayOf("g", "h", "i", "4", "G"),
            "F5" to arrayOf("j", "k", "l", "5", "J"),
            "F6" to arrayOf("m", "n", "o", "6", "M"),
            "F7" to arrayOf("p", "q", "r", "s", "7"),
            "F8" to arrayOf("t", "u", "v", "8", "T"),
            "F9" to arrayOf("w", "x", "y", "z", "9"),
            "F0" to arrayOf("-", "#", "0", "+", "="),
            "FS" to arrayOf(".", ",", "?", "!", "'")
        )

        val numericFlickMap = mapOf(
            "F1" to arrayOf("1", "/", "@", ":", "~"),
            "F2" to arrayOf("2", "!", "%", "\"", "'"),
            "F3" to arrayOf("3", "+", "*", "=", "^"),
            "F4" to arrayOf("4", "*", "&", ";", ":"),
            "F5" to arrayOf("5", "¥", "$", "€", "￦"),
            "F6" to arrayOf("6", "<", "^", ">", "="),
            "F7" to arrayOf("7", "#", "|", "〒", "※"),
            "F8" to arrayOf("8", "[", "]", "(", ")"),
            "F9" to arrayOf("9", "☆", "○", "♡", "◇"),
            "F0" to arrayOf("0", "-", "_", "+", "="),
            "FS" to arrayOf(".", ",", "?", "!", "。")
        )

        val kanaRowLabelMap = mapOf(
            "F1" to "あ行",
            "F2" to "か行",
            "F3" to "さ行",
            "F4" to "た行",
            "F5" to "な行",
            "F6" to "は行",
            "F7" to "ま行",
            "F8" to "や行",
            "F9" to "ら行",
            "F0" to "わ行",
            "FS" to "記号"
        )

        val alphaRowLabelMap = mapOf(
            "F1" to "@/:~",
            "F2" to "abc",
            "F3" to "def",
            "F4" to "ghi",
            "F5" to "jkl",
            "F6" to "mno",
            "F7" to "pqrs",
            "F8" to "tuv",
            "F9" to "wxyz",
            "F0" to "-#",
            "FS" to ".,?!"
        )

        val alphaCompactLabelMap = mapOf(
            "F1" to "@/:~",
            "F2" to "abc",
            "F3" to "def",
            "F4" to "ghi",
            "F5" to "jkl",
            "F6" to "mno",
            "F7" to "pqrs",
            "F8" to "tuv",
            "F9" to "wxyz",
            "F0" to "-#",
            "FS" to ".,?!"
        )

        val numericRowLabelMap = mapOf(
            "F1" to "1/@",
            "F2" to "2/!",
            "F3" to "3/+",
            "F4" to "4/&",
            "F5" to "5/¥",
            "F6" to "6/<",
            "F7" to "7/#",
            "F8" to "8/[]",
            "F9" to "9/☆",
            "F0" to "0/-",
            "FS" to ".,?!"
        )

        val numericCompactLabelMap = mapOf(
            "F1" to "1\n/:@~",
            "F2" to "2\n!%\"'",
            "F3" to "3\n+*=^",
            "F4" to "4\n*&;:",
            "F5" to "5\n¥$€￦",
            "F6" to "6\n<^>=",
            "F7" to "7\n#|〒※",
            "F8" to "8\n[]()",
            "F9" to "9\n☆○♡◇",
            "F0" to "0\n-_+=",
            "FS" to ".,?!\n。"
        )

        val builtInConversionCandidates = mapOf(
            "きょう" to listOf("今日"),
            "あした" to listOf("明日"),
            "きのう" to listOf("昨日"),
            "わたし" to listOf("私"),
            "にほん" to listOf("日本"),
            "とうきょう" to listOf("東京"),
            "おおさか" to listOf("大阪"),
            "しんじゅく" to listOf("新宿"),
            "しぶや" to listOf("渋谷"),
            "いけぶくろ" to listOf("池袋"),
            "よこはま" to listOf("横浜"),
            "なごや" to listOf("名古屋"),
            "きょうと" to listOf("京都"),
            "ふくおか" to listOf("福岡"),
            "さっぽろ" to listOf("札幌"),
            "まゆ" to listOf("麻祐", "真由", "万由"),
            "なーちゃん" to listOf("なーちゃん"),
            "こうた" to listOf("弘太", "康太"),
            "かんじ" to listOf("漢字"),
            "へんかん" to listOf("変換"),
            "じしょ" to listOf("辞書"),
            "よみ" to listOf("読み"),
            "たんご" to listOf("単語"),
            "たんごちょう" to listOf("単語帳"),
            "おんせい" to listOf("音声"),
            "にゅうりょく" to listOf("入力"),
            "おんせいにゅうりょく" to listOf("音声入力"),
            "きーぼーど" to listOf("キーボード"),
            "ふりっく" to listOf("フリック"),
            "てんきー" to listOf("テンキー"),
            "かな" to listOf("かな"),
            "かたかな" to listOf("カタカナ"),
            "あぷり" to listOf("アプリ"),
            "ぷれびゅー" to listOf("プレビュー"),
            "ふぉるだー" to listOf("フォルダー"),
            "だうんろーど" to listOf("ダウンロード"),
            "いんすとーる" to listOf("インストール"),
            "いんぽーと" to listOf("インポート"),
            "えくすぽーと" to listOf("エクスポート"),
            "らいん" to listOf("LINE", "ライン"),
            "ちゃっとじーぴーてぃー" to listOf("ChatGPT", "チャットGPT"),
            "おねがい" to listOf("お願い"),
            "よろしく" to listOf("よろしく"),
            "ありがとう" to listOf("ありがとう"),
            "すみません" to listOf("すみません"),
            "おつかれ" to listOf("お疲れ"),
            "かくにん" to listOf("確認"),
            "れんらく" to listOf("連絡"),
            "たいおう" to listOf("対応"),
            "しゅうせい" to listOf("修正"),
            "こうしん" to listOf("更新"),
            "へんしゅう" to listOf("編集"),
            "けんさく" to listOf("検索"),
            "せってい" to listOf("設定"),
            "びょういん" to listOf("病院"),
            "じかん" to listOf("時間"),
            "よてい" to listOf("予定"),
            "でんわ" to listOf("電話"),
            "めーる" to listOf("メール"),
            "かいぎ" to listOf("会議"),
            "しりょう" to listOf("資料"),
            "じぇみに" to listOf("Gemini", "ジェミニ"),
            "じぇみにえーぴーあい" to listOf("Gemini API"),
            "おーぷんえーあい" to listOf("OpenAI", "オープンAI"),
            "くろーど" to listOf("Claude", "クロード"),
            "こぱいろっと" to listOf("Copilot", "コパイロット"),
            "ぱーぷれきしてぃ" to listOf("Perplexity", "パープレキシティ"),
            "でぃーぷしーく" to listOf("DeepSeek", "ディープシーク"),
            "そら" to listOf("Sora", "ソラ"),
            "えるえるえむ" to listOf("LLM"),
            "らぐ" to listOf("RAG"),
            "えんべでぃんぐ" to listOf("Embedding", "埋め込み"),
            "えむしーぴー" to listOf("MCP"),
            "ふぁいんちゅーにんぐ" to listOf("ファインチューニング"),
            "ぷろんぷと" to listOf("プロンプト", "Prompt"),
            "えーじぇんと" to listOf("エージェント", "Agent"),
            "えーじぇんとえーぴーあい" to listOf("Agents API"),
            "れすぽんすえーぴーあい" to listOf("Responses API"),
            "りあるたいむえーぴーあい" to listOf("Realtime API"),
            "べくたーでーたべーす" to listOf("ベクターデータベース", "Vector Database"),
            "あんちぐらびてぃ" to listOf("AntiGravity", "アンチグラビティ"),
            "くらうどふれあ" to listOf("Cloudflare"),
            "ばーせる" to listOf("Vercel"),
            "ねっとりふぁい" to listOf("Netlify"),
            "れんだー" to listOf("Render"),
            "どっかー" to listOf("Docker"),
            "きゅーばねてぃす" to listOf("Kubernetes", "K8s"),
            "ぎっとはぶ" to listOf("GitHub"),
            "ばろらんと" to listOf("VALORANT", "ヴァロラント"),
            "えいぺっくす" to listOf("Apex", "Apex Legends"),
            "ふぉーとないと" to listOf("Fortnite"),
            "まいんくらふと" to listOf("Minecraft"),
            "ろぶろっくす" to listOf("Roblox"),
            "げんしん" to listOf("原神"),
            "ぱるわーるど" to listOf("Palworld"),
            "えるでんりんぐ" to listOf("エルデンリング"),
            "もんはんわいるず" to listOf("モンハンワイルズ", "モンスターハンターワイルズ"),
            "ほんじつ" to listOf("本日"),
            "らいしゅう" to listOf("来週"),
            "こんしゅう" to listOf("今週"),
            "らいげつ" to listOf("来月"),
            "こんげつ" to listOf("今月"),
            "えらー" to listOf("エラー"),
            "さいしどう" to listOf("再起動"),
            "きゃっしゅ" to listOf("キャッシュ"),
            "せいこう" to listOf("成功"),
            "しっぱい" to listOf("失敗")
        )

        val dakutenMap = mapOf(
            'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
            'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
            'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
            'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
            'う' to 'ゔ',
            'が' to 'か', 'ぎ' to 'き', 'ぐ' to 'く', 'げ' to 'け', 'ご' to 'こ',
            'ざ' to 'さ', 'じ' to 'し', 'ず' to 'す', 'ぜ' to 'せ', 'ぞ' to 'そ',
            'だ' to 'た', 'ぢ' to 'ち', 'づ' to 'つ', 'で' to 'て', 'ど' to 'と',
            'ば' to 'は', 'び' to 'ひ', 'ぶ' to 'ふ', 'べ' to 'へ', 'ぼ' to 'ほ',
            'ゔ' to 'う', 'ぱ' to 'ば', 'ぴ' to 'び', 'ぷ' to 'ぶ', 'ぺ' to 'べ', 'ぽ' to 'ぼ',
            'カ' to 'ガ', 'キ' to 'ギ', 'ク' to 'グ', 'ケ' to 'ゲ', 'コ' to 'ゴ',
            'サ' to 'ザ', 'シ' to 'ジ', 'ス' to 'ズ', 'セ' to 'ゼ', 'ソ' to 'ゾ',
            'タ' to 'ダ', 'チ' to 'ヂ', 'ツ' to 'ヅ', 'テ' to 'デ', 'ト' to 'ド',
            'ハ' to 'バ', 'ヒ' to 'ビ', 'フ' to 'ブ', 'ヘ' to 'ベ', 'ホ' to 'ボ',
            'ウ' to 'ヴ',
            'ガ' to 'カ', 'ギ' to 'キ', 'グ' to 'ク', 'ゲ' to 'ケ', 'ゴ' to 'コ',
            'ザ' to 'サ', 'ジ' to 'シ', 'ズ' to 'ス', 'ゼ' to 'セ', 'ゾ' to 'ソ',
            'ダ' to 'タ', 'ヂ' to 'チ', 'ヅ' to 'ツ', 'デ' to 'テ', 'ド' to 'ト',
            'バ' to 'ハ', 'ビ' to 'ヒ', 'ブ' to 'フ', 'ベ' to 'ヘ', 'ボ' to 'ホ',
            'ヴ' to 'ウ', 'パ' to 'バ', 'ピ' to 'ビ', 'プ' to 'ブ', 'ペ' to 'ベ', 'ポ' to 'ボ'
        )

        val handakutenMap = mapOf(
            'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
            'ば' to 'ぱ', 'び' to 'ぴ', 'ぶ' to 'ぷ', 'べ' to 'ぺ', 'ぼ' to 'ぽ',
            'ぱ' to 'は', 'ぴ' to 'ひ', 'ぷ' to 'ふ', 'ぺ' to 'へ', 'ぽ' to 'ほ',
            'ハ' to 'パ', 'ヒ' to 'ピ', 'フ' to 'プ', 'ヘ' to 'ペ', 'ホ' to 'ポ',
            'バ' to 'パ', 'ビ' to 'ピ', 'ブ' to 'プ', 'ベ' to 'ペ', 'ボ' to 'ポ',
            'パ' to 'ハ', 'ピ' to 'ヒ', 'プ' to 'フ', 'ペ' to 'ヘ', 'ポ' to 'ホ'
        )

        val smallKanaToggleMap = mapOf(
            'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
            'ぁ' to 'あ', 'ぃ' to 'い', 'ぅ' to 'う', 'ぇ' to 'え', 'ぉ' to 'お',
            'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ', 'つ' to 'っ', 'わ' to 'ゎ',
            'ゃ' to 'や', 'ゅ' to 'ゆ', 'ょ' to 'よ', 'っ' to 'つ', 'ゎ' to 'わ',
            'か' to 'ゕ', 'け' to 'ゖ', 'ゕ' to 'か', 'ゖ' to 'け',
            'ア' to 'ァ', 'イ' to 'ィ', 'ウ' to 'ゥ', 'エ' to 'ェ', 'オ' to 'ォ',
            'ァ' to 'ア', 'ィ' to 'イ', 'ゥ' to 'ウ', 'ェ' to 'エ', 'ォ' to 'オ',
            'ヤ' to 'ャ', 'ユ' to 'ュ', 'ヨ' to 'ョ', 'ツ' to 'ッ', 'ワ' to 'ヮ',
            'ャ' to 'ヤ', 'ュ' to 'ユ', 'ョ' to 'ヨ', 'ッ' to 'ツ', 'ヮ' to 'ワ',
            'カ' to 'ヵ', 'ケ' to 'ヶ', 'ヵ' to 'カ', 'ヶ' to 'ケ'
        )

        val handakutenPriorityChars = setOf(
            'ば', 'び', 'ぶ', 'べ', 'ぼ',
            'バ', 'ビ', 'ブ', 'ベ', 'ボ'
        )

        val dakutenForwardMap = mapOf(
            'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
            'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
            'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
            'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
            'う' to 'ゔ',
            'カ' to 'ガ', 'キ' to 'ギ', 'ク' to 'グ', 'ケ' to 'ゲ', 'コ' to 'ゴ',
            'サ' to 'ザ', 'シ' to 'ジ', 'ス' to 'ズ', 'セ' to 'ゼ', 'ソ' to 'ゾ',
            'タ' to 'ダ', 'チ' to 'ヂ', 'ツ' to 'ヅ', 'テ' to 'デ', 'ト' to 'ド',
            'ハ' to 'バ', 'ヒ' to 'ビ', 'フ' to 'ブ', 'ヘ' to 'ベ', 'ホ' to 'ボ',
            'ウ' to 'ヴ'
        )

        val handakutenForwardMap = mapOf(
            'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
            'ば' to 'ぱ', 'び' to 'ぴ', 'ぶ' to 'ぷ', 'べ' to 'ぺ', 'ぼ' to 'ぽ',
            'ハ' to 'パ', 'ヒ' to 'ピ', 'フ' to 'プ', 'ヘ' to 'ペ', 'ホ' to 'ポ',
            'バ' to 'パ', 'ビ' to 'ピ', 'ブ' to 'プ', 'ベ' to 'ペ', 'ボ' to 'ポ'
        )

        val smallKanaBaseToSmallMap = mapOf(
            'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
            'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ', 'つ' to 'っ', 'わ' to 'ゎ',
            'か' to 'ゕ', 'け' to 'ゖ',
            'ア' to 'ァ', 'イ' to 'ィ', 'ウ' to 'ゥ', 'エ' to 'ェ', 'オ' to 'ォ',
            'ヤ' to 'ャ', 'ユ' to 'ュ', 'ヨ' to 'ョ', 'ツ' to 'ッ', 'ワ' to 'ヮ',
            'カ' to 'ヵ', 'ケ' to 'ヶ'
        )

        val smallKanaSmallToBaseMap = smallKanaBaseToSmallMap.entries.associate { (base, small) ->
            small to base
        }

        val looseBoundaryChars = setOf(
            ' ', '\n', '\t',
            '、', '。', '！', '？', '!', '?',
            '（', '）', '(', ')', '「', '」', '『', '』', '【', '】',
            '・', 'ー', '〜', '~',
            'は', 'が', 'を', 'に', 'へ', 'で', 'と', 'も', 'や', 'の', 'ね', 'よ', 'か', 'な'
        )

        val honorificOrParticleSuffixes = listOf(
            "ちゃん", "さん", "くん", "君", "さま", "様", "氏", "先輩", "せんぱい", "先生", "せんせい",
            "は", "が", "を", "に", "へ", "で", "と", "も", "ね", "よ", "か", "な", "の", "って"
        )
    }
}

