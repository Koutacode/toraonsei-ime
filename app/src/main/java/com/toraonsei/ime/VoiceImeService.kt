package com.toraonsei.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.toraonsei.R
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

class VoiceImeService : InputMethodService(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: UserDictionaryRepository
    private lateinit var speechController: SpeechController

    private val suggestionEngine = SuggestionEngine()
    private val formatter = LocalFormatter()

    private var dictionaryEntries: List<DictionaryEntry> = emptyList()
    private var recordingRequested = false
    private var isRecognizerActive = false
    private var manualKeyboardMode: ManualKeyboardMode = ManualKeyboardMode.TENKEY
    private var restartListeningJob: Job? = null

    private var lastCommittedText: String = ""
    private var lastSelectedCandidate: String = ""
    private var lastVoiceCommittedText: String = ""

    private val appMemory = mutableMapOf<String, AppBuffer>()
    private val flickStartByButtonId = mutableMapOf<Int, Pair<Float, Float>>()

    private var rootView: View? = null
    private var candidateContainer: LinearLayout? = null
    private var addWordButton: Button? = null
    private var micButton: Button? = null
    private var formatContextButton: Button? = null
    private var statusText: TextView? = null
    private var keyModeTextButton: Button? = null
    private var keyModeTenButton: Button? = null
    private var keyBackspaceButton: Button? = null
    private var flickGuideText: TextView? = null
    private var textKeyboardPanel: LinearLayout? = null
    private var tenKeyPanel: LinearLayout? = null

    private var addWordPanel: LinearLayout? = null
    private var addWordEdit: EditText? = null
    private var addReadingEdit: EditText? = null
    private var saveWordButton: Button? = null
    private var cancelWordButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        repository = UserDictionaryRepository(this)
        speechController = SpeechController(this, this)

        serviceScope.launch {
            repository.entriesFlow.collectLatest { entries ->
                dictionaryEntries = entries
                refreshSuggestions()
            }
        }
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        rootView = view
        bindViews(view)
        setupListeners()
        applyManualKeyboardMode()
        updateMicState()
        refreshSuggestions()
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        refreshSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setStatus("待機中")
        refreshSuggestions()
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
    }

    override fun onDestroy() {
        stopRecordingIfNeeded(forceCancel = true)
        restartListeningJob?.cancel()
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

    override fun onFinal(text: String) {
        val corrected = applyDictionaryCorrections(text.trim())
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
        candidateContainer = view.findViewById(R.id.candidateContainer)
        addWordButton = view.findViewById(R.id.addWordButton)
        micButton = view.findViewById(R.id.micButton)
        formatContextButton = view.findViewById(R.id.formatContextButton)
        statusText = view.findViewById(R.id.statusText)
        keyModeTextButton = view.findViewById(R.id.keyModeTextButton)
        keyModeTenButton = view.findViewById(R.id.keyModeTenButton)
        keyBackspaceButton = view.findViewById(R.id.keyBackspaceButton)
        flickGuideText = view.findViewById(R.id.flickGuideText)
        textKeyboardPanel = view.findViewById(R.id.textKeyboardPanel)
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

        keyModeTextButton?.setOnClickListener {
            manualKeyboardMode = ManualKeyboardMode.TEXT
            applyManualKeyboardMode()
        }

        keyModeTenButton?.setOnClickListener {
            manualKeyboardMode = ManualKeyboardMode.TENKEY
            applyManualKeyboardMode()
        }

        keyBackspaceButton?.setOnClickListener {
            handleBackspace()
        }

        addReadingEdit?.doAfterTextChanged { editable ->
            saveWordButton?.isEnabled = !editable.isNullOrBlank()
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
        val isText = manualKeyboardMode == ManualKeyboardMode.TEXT
        textKeyboardPanel?.isVisible = isText
        tenKeyPanel?.isVisible = !isText
        flickGuideText?.isVisible = !isText
        if (!isText) {
            hideFlickGuide()
        }
        keyModeTextButton?.alpha = if (isText) 1f else 0.65f
        keyModeTenButton?.alpha = if (!isText) 1f else 0.65f
    }

    private fun bindTaggedKeyButtons(root: View) {
        if (root is Button) {
            val tag = root.tag as? String
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
                            val output = resolveFlickKana(tag, direction)
                            if (output.isNotBlank()) {
                                commitTextDirect(output)
                            }
                            hideFlickGuide()
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            flickStartByButtonId.remove(button.id)
                            hideFlickGuide()
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
        val table = flickMap[tag] ?: return ""
        return when (direction) {
            FlickDirection.CENTER -> table[0]
            FlickDirection.LEFT -> table[1]
            FlickDirection.UP -> table[2]
            FlickDirection.RIGHT -> table[3]
            FlickDirection.DOWN -> table[4]
        }
    }

    private fun handleTaggedKeyInput(tag: String) {
        when (tag) {
            "SPACE" -> commitTextDirect(" ")
            "ENTER" -> commitTextDirect("\n")
            "DAKUTEN" -> applyDakuten()
            "HANDAKUTEN" -> applyHandakuten()
            "SMALL" -> applySmallKana()
            else -> commitTextDirect(tag)
        }
    }

    private fun showFlickGuide(tag: String, direction: FlickDirection) {
        val table = flickMap[tag] ?: return
        val rowLabel = flickRowLabelMap[tag].orEmpty()

        val items = listOf(
            buildGuideItem("上", table[2], direction == FlickDirection.UP),
            buildGuideItem("左", table[1], direction == FlickDirection.LEFT),
            buildGuideItem("中", table[0], direction == FlickDirection.CENTER),
            buildGuideItem("右", table[3], direction == FlickDirection.RIGHT),
            buildGuideItem("下", table[4], direction == FlickDirection.DOWN)
        )

        val prefix = if (rowLabel.isNotBlank()) "$rowLabel  " else ""
        flickGuideText?.text = prefix + items.joinToString("  ")
    }

    private fun hideFlickGuide() {
        flickGuideText?.text = defaultFlickGuideText
    }

    private fun buildGuideItem(label: String, value: String, selected: Boolean): String {
        val base = "$label:$value"
        return if (selected) "【$base】" else base
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
        speechController.startListening()
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
        currentInputConnection?.commitText(text, 1)
        if (text.isNotBlank()) {
            lastCommittedText = text
        }
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
        addReadingEdit?.requestFocus()
        saveWordButton?.isEnabled = false
    }

    private fun hideAddWordPanel() {
        addWordPanel?.isVisible = false
        addWordEdit?.clearFocus()
        addReadingEdit?.clearFocus()
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

        suggestions.take(5).forEach { suggestion ->
            val button = Button(this).apply {
                text = suggestion
                textSize = 13f
                isAllCaps = false
                minWidth = dp(84)
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
                dp(40)
            )
            params.marginEnd = dp(6)
            container.addView(button, params)
        }
    }

    private fun commitAndTrack(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)

        if (text.isNotBlank()) {
            lastCommittedText = text
            val pkg = currentPackageName()
            val memory = appMemory.getOrPut(pkg) { AppBuffer() }
            memory.appendHistory(text)
            memory.addRecentPhrase(text)
        }
        refreshSuggestions()
    }

    private fun getCurrentBeforeCursor(): String {
        return currentInputConnection
            ?.getTextBeforeCursor(200, 0)
            ?.toString()
            .orEmpty()
    }

    private fun getCurrentAfterCursor(): String {
        return currentInputConnection
            ?.getTextAfterCursor(50, 0)
            ?.toString()
            .orEmpty()
    }

    private fun currentPackageName(): String {
        return currentInputEditorInfo?.packageName ?: "unknown"
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

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
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

    private enum class ManualKeyboardMode {
        TEXT,
        TENKEY
    }

    private enum class FlickDirection {
        CENTER, UP, RIGHT, DOWN, LEFT
    }

    private companion object {
        const val defaultFlickGuideText = "キーを押したまま動かすと、上/右/下/左の文字候補を表示"

        val flickMap = mapOf(
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

        val flickRowLabelMap = mapOf(
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
