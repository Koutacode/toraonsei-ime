package com.toraonsei.ime

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.toraonsei.R
import com.toraonsei.dict.LocalKanaKanjiDictionary
import com.toraonsei.dict.UserDictionaryRepository
import com.toraonsei.format.LocalFormatter
import com.toraonsei.model.DictionaryEntry
import com.toraonsei.model.RecentPhrase
import com.toraonsei.speech.SpeechController
import com.toraonsei.suggest.SuggestionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class VoiceImeService : InputMethodService(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: UserDictionaryRepository
    private lateinit var localDictionary: LocalKanaKanjiDictionary
    private lateinit var speechController: SpeechController

    private val suggestionEngine = SuggestionEngine()
    private val formatter = LocalFormatter()

    private var dictionaryEntries: List<DictionaryEntry> = emptyList()
    private var recordingRequested = false
    private var isRecognizerActive = false
    private var restartListeningJob: Job? = null
    private var inputMode: InputMode = InputMode.KANA
    private var alphaUppercase = false

    private var lastCommittedText: String = ""
    private var lastSelectedCandidate: String = ""
    private var lastVoiceCommittedText: String = ""

    private val appMemory = mutableMapOf<String, AppBuffer>()
    private val flickStartByButtonId = mutableMapOf<Int, Pair<Float, Float>>()
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
    private var micButton: Button? = null
    private var formatContextButton: Button? = null
    private var statusText: TextView? = null
    private var inputModeButton: Button? = null
    private var smallActionButton: Button? = null
    private var keyBackspaceButton: Button? = null
    private var flickGuideText: TextView? = null
    private var tenKeyPanel: LinearLayout? = null

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

    override fun onCreate() {
        super.onCreate()
        repository = UserDictionaryRepository(this)
        localDictionary = LocalKanaKanjiDictionary(this)
        speechController = SpeechController(this, this)

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
        val view = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        rootView = view
        bindViews(view)
        setupListeners()
        applyManualKeyboardMode()
        applyImeSizingPolicy()
        updateMicState()
        refreshSuggestions()
        view.post {
            applyImeSizingPolicy()
            refreshSuggestions()
        }
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        refreshSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyImeSizingPolicy()
        setStatus("待機中")
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
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRecordingIfNeeded(forceCancel = true)
        dismissFlickPopup()
    }

    override fun onDestroy() {
        stopRecordingIfNeeded(forceCancel = true)
        restartListeningJob?.cancel()
        dismissFlickPopup()
        speechController.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onReady() {
        isRecognizerActive = true
        setStatus("録音中...")
    }

    override fun onPartial(text: String) {
        if (text.isNotBlank()) {
            setStatus("認識中(途中結果)")
        }
    }

    override fun onFinal(text: String, alternatives: List<String>) {
        val corrected = selectBestRecognitionCandidate(
            alternatives = alternatives.ifEmpty { listOf(text) }
        )
        if (corrected.isBlank()) {
            setStatus("認識結果が空でした")
            return
        }
        commitAndTrack(corrected)
        lastVoiceCommittedText = corrected
        setStatus("音声入力しました")
    }

    override fun onError(message: String) {
        isRecognizerActive = false
        if (recordingRequested) {
            setStatus("録音継続中: 再開します")
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
        } else {
            updateMicState()
        }
    }

    private fun bindViews(view: View) {
        keyboardRoot = view.findViewById(R.id.keyboardRoot)
        candidateScroll = view.findViewById(R.id.candidateScroll)
        recordRow = view.findViewById(R.id.recordRow)
        manualInputPanel = view.findViewById(R.id.manualInputPanel)
        candidateContainer = view.findViewById(R.id.candidateContainer)
        addWordButton = view.findViewById(R.id.addWordButton)
        micButton = view.findViewById(R.id.micButton)
        formatContextButton = view.findViewById(R.id.formatContextButton)
        statusText = view.findViewById(R.id.statusText)
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

    private fun setupListeners() {
        rootView?.let {
            bindTaggedKeyButtons(it)
            bindFlickTenKeys(it)
            styleFlickTenKeys(it)
        }

        addWordButton?.setOnClickListener { openAddWordPanel() }

        micButton?.setOnClickListener {
            if (recordingRequested) {
                stopRecordingIfNeeded()
            } else {
                startRecordingIfAllowed()
            }
        }

        formatContextButton?.setOnClickListener {
            formatLastVoiceInputWithContext()
        }

        keyBackspaceButton?.setOnClickListener {
            handleBackspace()
        }

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
    }

    private fun applyManualKeyboardMode() {
        tenKeyPanel?.isVisible = true
        flickGuideText?.isVisible = true
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
            if (!tag.isNullOrBlank() && !tag.startsWith("F")) {
                root.setOnClickListener { handleTaggedKeyInput(tag) }
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
            if (!tag.isNullOrBlank() && tag.startsWith("F")) {
                root.setOnTouchListener { button, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            flickStartByButtonId[button.id] = event.x to event.y
                            showFlickGuide(tag, FlickDirection.CENTER)
                            showFlickPopup(button, tag, FlickDirection.CENTER)
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
                            showFlickGuide(tag, direction)
                            showFlickPopup(button, tag, direction)
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val start = flickStartByButtonId.remove(button.id) ?: (event.x to event.y)
                            val direction = resolveFlickDirection(
                                startX = start.first,
                                startY = start.second,
                                endX = event.x,
                                endY = event.y
                            )
                            if (direction == FlickDirection.CENTER) {
                                handleCenterTapToggle(tag)
                            } else {
                                clearToggleTapState()
                                val output = resolveFlickKana(tag, direction)
                                if (output.isNotBlank()) {
                                    commitTextDirect(output)
                                }
                            }
                            hideFlickGuide()
                            dismissFlickPopup()
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            flickStartByButtonId.remove(button.id)
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
        if (abs(dx) < dp(18) && abs(dy) < dp(18)) {
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
        clearToggleTapState()
        if (tag != "CONVERT") {
            clearConversionState()
        }
        when (tag) {
            "SPACE" -> commitTextDirect(" ")
            "ENTER" -> commitTextDirect("\n")
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

    private fun handleSmallAction() {
        when (inputMode) {
            InputMode.KANA -> applySmallKana()
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
            InputMode.KANA -> "小"
            InputMode.ALPHA -> "A/a"
            InputMode.NUMERIC -> "…"
        }
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
            InputMode.KANA -> "タップ連打: あ→い→う→え→お / フリック: 左=い 上=う 右=え 下=お"
            InputMode.ALPHA -> "英字配列: abc/def... / フリック: 左→上→右→下 / A/aで大文字切替"
            InputMode.NUMERIC -> "数字配列: 1-9/0 / フリックで記号入力 / …キーで省略記号"
        }
    }

    private fun showFlickGuide(tag: String, direction: FlickDirection) {
        val table = activeFlickMap()[tag] ?: return
        val rowLabel = activeRowLabelMap()[tag].orEmpty()

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
        val table = activeFlickMap()[tag] ?: return ""
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

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = location[0] + (anchor.width - popupWidth) / 2
        val y = location[1] - popupHeight - dp(8)

        val parent = rootView ?: anchor
        try {
            if (popup.isShowing) {
                popup.update(x, y, popupWidth, popupHeight)
            } else {
                popup.showAtLocation(parent, Gravity.NO_GRAVITY, x, y)
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
            isClippingEnabled = false
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
            commitTextDirect("゛")
        }
    }

    private fun applyHandakuten() {
        val changed = replacePreviousChar(handakutenMap)
        if (!changed) {
            commitTextDirect("゜")
        }
    }

    private fun applySmallKana() {
        val changed = replacePreviousChar(smallKanaMap)
        if (!changed) {
            commitTextDirect("っ")
        }
    }

    private fun replacePreviousChar(map: Map<Char, Char>): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isEmpty()) return false
        val target = map[before.last()] ?: return false
        ic.deleteSurroundingText(1, 0)
        ic.commitText(target.toString(), 1)
        refreshSuggestions()
        return true
    }

    private fun startRecordingIfAllowed() {
        if (recordingRequested) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            setStatus("マイク権限がありません")
            showToast("設定画面でマイク権限を許可してください")
            return
        }

        recordingRequested = true
        isRecognizerActive = false
        restartListeningJob?.cancel()
        updateMicState()
        startListeningSession()
    }

    private fun stopRecordingIfNeeded(forceCancel: Boolean = false) {
        if (!recordingRequested && !isRecognizerActive) return
        recordingRequested = false
        restartListeningJob?.cancel()
        if (forceCancel) {
            speechController.cancel()
        } else {
            speechController.stopListening()
        }
        isRecognizerActive = false
        updateMicState()
        setStatus("待機中")
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
            .take(120)
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
            localDictionary.candidatesFor(reading, limit = 2).forEach { candidate ->
                hints.add(candidate)
            }
        }

        val pkg = currentPackageName()
        appMemory[pkg]
            ?.toRecentPhrases()
            ?.sortedByDescending { it.frequency }
            ?.take(20)
            ?.forEach { phrase ->
                if (phrase.text.isNotBlank()) {
                    hints.add(phrase.text)
                }
            }

        return hints.take(80)
    }

    private fun updateMicState() {
        val button = micButton ?: return
        if (recordingRequested) {
            button.text = "録音停止"
            button.setBackgroundResource(R.drawable.bg_mic_active)
        } else {
            button.text = "録音開始"
            button.setBackgroundResource(R.drawable.bg_chip)
        }
    }

    private fun formatLastVoiceInputWithContext() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty().trim()
        val source = when {
            selected.isNotBlank() -> selected
            lastVoiceCommittedText.isNotBlank() -> lastVoiceCommittedText
            else -> lastCommittedText
        }
        if (source.isBlank()) {
            setStatus("先に音声入力してください")
            return
        }
        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        val pkg = currentPackageName()
        val history = appMemory[pkg]?.history?.toString().orEmpty()

        val formatted = formatter.formatWithContext(
            input = source,
            beforeCursor = before,
            afterCursor = after,
            appHistory = history,
            dictionaryWords = dictionaryEntries.map { it.word }.toSet()
        )
        if (formatted.isBlank()) {
            setStatus("整形結果が空でした")
            return
        }
        if (formatted == source) {
            setStatus("文面整形: 変更なし")
            return
        }

        if (selected.isNotBlank()) {
            ic.commitText(formatted, 1)
        } else if (before.endsWith(source)) {
            ic.deleteSurroundingText(source.length, 0)
            ic.commitText(formatted, 1)
        } else {
            ic.commitText(formatted, 1)
        }

        lastCommittedText = formatted
        lastVoiceCommittedText = formatted
        val memory = appMemory.getOrPut(pkg) { AppBuffer() }
        memory.appendHistory(formatted)
        memory.addRecentPhrase(formatted)
        refreshSuggestions()
        setStatus("文面を整形しました")
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
        if (text.isEmpty()) return
        clearConversionState()
        if (commitTextToAddWordEditor(text)) {
            return
        }
        currentInputConnection?.commitText(text, 1)
        rememberCommittedText(text)
        refreshSuggestions()
    }

    private fun setStatus(message: String) {
        statusText?.text = message
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
        val before = getCurrentBeforeCursor()
        val after = getCurrentAfterCursor()
        val pkg = currentPackageName()
        val memory = appMemory.getOrPut(pkg) { AppBuffer() }

        val suggestions = suggestionEngine.generate(
            beforeCursor = before,
            afterCursor = after,
            appHistory = memory.history.toString(),
            dictionary = dictionaryEntries,
            recentPhrases = memory.toRecentPhrases()
        )
        renderCandidates(suggestions)
    }

    private fun renderCandidates(suggestions: List<String>) {
        val container = candidateContainer ?: return
        container.removeAllViews()
        val chipHeight = candidateChipHeightPx.takeIf { it > 0 } ?: dp(40)
        val chipMinWidth = candidateChipMinWidthPx.takeIf { it > 0 } ?: dp(84)
        val chipTextSize = candidateChipTextSizeSp

        suggestions.take(5).forEach { suggestion ->
            val button = Button(this).apply {
                text = suggestion
                textSize = chipTextSize
                isAllCaps = false
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

    private fun commitAndTrack(text: String) {
        if (text.isEmpty()) return
        clearToggleTapState()
        clearConversionState()
        currentInputConnection?.commitText(text, 1)

        rememberCommittedText(text)
        refreshSuggestions()
    }

    private fun getCurrentBeforeCursor(): String {
        return currentInputConnection
            ?.getTextBeforeCursor(contextWindowSize, 0)
            ?.toString()
            .orEmpty()
    }

    private fun getCurrentAfterCursor(): String {
        return currentInputConnection
            ?.getTextAfterCursor(contextWindowSize, 0)
            ?.toString()
            .orEmpty()
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
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val existing = conversionState

        if (
            existing != null &&
            now - existing.timestampMs <= convertCycleWindowMs &&
            existing.candidates.size > 1 &&
            before.endsWith(existing.currentOutput)
        ) {
            val nextIndex = (existing.index + 1) % existing.candidates.size
            val next = existing.candidates[nextIndex]
            ic.deleteSurroundingText(existing.currentOutput.length, 0)
            ic.commitText(next, 1)
            rememberCommittedText(next)
            conversionState = existing.copy(
                index = nextIndex,
                currentOutput = next,
                timestampMs = now
            )
            setStatus("変換 ${nextIndex + 1}/${existing.candidates.size}")
            refreshSuggestions()
            return
        }

        val sourceReading = Regex("([ぁ-ゖァ-ヺー]+)$").find(before)?.groupValues?.get(1).orEmpty()
        val reading = normalizeReadingKana(sourceReading)
        if (reading.isBlank()) {
            setStatus("かなを入力してから変換してください")
            return
        }

        val candidates = buildConversionCandidates(reading)
        if (candidates.isEmpty()) {
            setStatus("変換候補なし: 単語帳に登録してください")
            return
        }

        val first = candidates.first()
        ic.deleteSurroundingText(sourceReading.length, 0)
        ic.commitText(first, 1)
        rememberCommittedText(first)
        conversionState = ConversionState(
            reading = reading,
            candidates = candidates,
            index = 0,
            currentOutput = first,
            timestampMs = now
        )
        setStatus(
            if (candidates.size > 1) "変換 1/${candidates.size}" else "変換しました"
        )
        refreshSuggestions()
    }

    private fun buildConversionCandidates(reading: String): List<String> {
        val dictionaryCandidates = dictionaryEntries
            .asSequence()
            .mapNotNull { entry ->
                val normalizedReading = normalizeReadingKana(entry.readingKana)
                if (normalizedReading == reading) entry else null
            }
            .sortedWith(
                compareByDescending<DictionaryEntry> { it.priority }
                    .thenByDescending { it.createdAt }
            )
            .map { it.word.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val builtInExact = builtInConversionCandidates[reading].orEmpty()
        val localExact = localDictionary.candidatesFor(reading, limit = 10)
        val rules = buildConversionRules()
        val greedy = applyLongestRuleConversion(reading, rules)
        val katakana = hiraganaToKatakana(reading)

        val merged = linkedSetOf<String>()
        dictionaryCandidates.forEach { merged.add(it) }
        builtInExact.forEach { merged.add(it) }
        localExact.forEach { merged.add(it) }
        if (greedy.replacedCount > 0 && greedy.output.isNotBlank()) {
            merged.add(greedy.output)
        }
        if (katakana.isNotBlank() && katakana != reading) {
            merged.add(katakana)
        }
        if (!merged.contains(reading)) {
            merged.add(reading)
        }
        return merged.take(12)
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
        val contextTokens = (extractJapaneseTokens(before) + extractJapaneseTokens(after) + extractJapaneseTokens(history))
            .takeLast(24)
            .toSet()

        val scored = alternatives
            .map { raw ->
                val corrected = applyDictionaryCorrections(raw.trim())
                val score = scoreRecognitionCandidate(corrected, contextTokens)
                RecognizedCandidate(
                    original = raw,
                    corrected = corrected,
                    score = score
                )
            }
            .sortedByDescending { it.score }

        return scored.firstOrNull()?.corrected.orEmpty()
    }

    private fun scoreRecognitionCandidate(candidate: String, contextTokens: Set<String>): Int {
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

        if (candidate.any { it in '一'..'龯' }) {
            score += 22
        }
        if (candidate.any { it in 'ァ'..'ン' }) {
            score += 10
        }

        return score
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

        val exact = dictionaryEntries.firstOrNull { it.readingKana == input }
        if (exact != null) {
            return exact.word
        }

        val rules = dictionaryEntries
            .mapNotNull { entry ->
                val from = entry.readingKana.trim()
                val to = entry.word.trim()
                if (from.isBlank() || to.isBlank()) null else from to to
            }
            .sortedByDescending { it.first.length }

        if (rules.isEmpty()) {
            return input
        }

        val out = StringBuilder()
        var index = 0
        while (index < input.length) {
            val matched = rules.firstOrNull { (from, _) ->
                index + from.length <= input.length &&
                    input.regionMatches(index, from, 0, from.length) &&
                    hasWordBoundary(input, index, from.length)
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

        val targetImeHeight = (windowHeight * when {
            sw >= 600 && isLandscape -> 0.42f
            sw >= 600 -> 0.36f
            isLandscape -> 0.50f
            else -> 0.44f
        }).roundToInt().coerceIn(
            dp(if (sw >= 600) 320 else 270),
            dp(if (sw >= 600) 620 else if (isLandscape) 460 else 560)
        )

        val candidateHeight = (targetImeHeight * 0.105f).roundToInt().coerceIn(
            dp(if (sw >= 600) 48 else 36),
            dp(if (sw >= 600) 74 else 56)
        )
        val recordButtonHeight = (targetImeHeight * if (sw >= 600) 0.18f else 0.16f).roundToInt().coerceIn(
            dp(if (sw >= 600) 82 else 62),
            dp(if (sw >= 600) 130 else 98)
        )
        val tenKeyHeight = (targetImeHeight * if (sw >= 600) 0.172f else 0.168f).roundToInt().coerceIn(
            dp(if (sw >= 600) 84 else 56),
            dp(if (sw >= 600) 128 else 102)
        )
        val horizontalPadding = (targetImeHeight * 0.015f).roundToInt().coerceIn(
            dp(4),
            dp(if (sw >= 600) 16 else 10)
        )
        val manualPanelPadding = (targetImeHeight * 0.018f).roundToInt().coerceIn(
            dp(6),
            dp(if (sw >= 600) 14 else 10)
        )
        val guideTextSizeSp = if (sw >= 600) 14f else if (isLandscape) 10.5f else 11f
        val statusTextSizeSp = if (sw >= 600) 15f else if (isLandscape) 11.5f else 12f
        val flickTextSizeSp = if (sw >= 600) 18f else if (tenKeyHeight >= dp(84)) 14f else 12.5f

        candidateChipHeightPx = (candidateHeight * 0.88f).roundToInt()
        candidateChipMinWidthPx = (windowWidth * if (sw >= 600) 0.13f else 0.18f).roundToInt().coerceAtLeast(dp(72))
        candidateChipTextSizeSp = if (sw >= 600) 15f else if (isLandscape) 12f else 13f
        flickKeyTextSizeSp = flickTextSizeSp
        flickKeyPaddingPx = (tenKeyHeight * 0.045f).roundToInt().coerceIn(dp(1), dp(4))

        keyboardRoot?.setPadding(horizontalPadding, horizontalPadding, horizontalPadding, horizontalPadding)
        updateViewHeight(candidateScroll, candidateHeight)
        updateViewHeight(addWordButton, (candidateHeight * 0.92f).roundToInt())
        updateViewHeight(micButton, recordButtonHeight)
        updateViewHeight(formatContextButton, recordButtonHeight)
        manualInputPanel?.setPadding(manualPanelPadding, manualPanelPadding, manualPanelPadding, manualPanelPadding)
        statusText?.textSize = statusTextSizeSp
        flickGuideText?.textSize = guideTextSizeSp

        resizeTenKeyButtons(root, tenKeyHeight)
        styleFlickTenKeys(root)
    }

    private fun resizeTenKeyButtons(root: View, heightPx: Int) {
        if (root is Button) {
            val tag = root.tag as? String
            if (!tag.isNullOrBlank() && isTenKeyTag(tag)) {
                updateViewHeight(root, heightPx)
            }
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                resizeTenKeyButtons(root.getChildAt(i), heightPx)
            }
        }
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

    private enum class AddWordField {
        WORD,
        READING
    }

    private companion object {
        const val toggleTapWindowMs = 850L
        const val convertCycleWindowMs = 2200L
        const val contextWindowSize = 200
        val tokenRegex = Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}A-Za-z0-9]{1,16}")

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
            'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ'
        )

        val handakutenMap = mapOf(
            'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ'
        )

        val smallKanaMap = mapOf(
            'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
            'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ', 'つ' to 'っ', 'わ' to 'ゎ'
        )
    }
}
