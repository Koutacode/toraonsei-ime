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
import com.toraonsei.model.ImeMode
import com.toraonsei.model.RecentPhrase
import com.toraonsei.speech.SpeechController
import com.toraonsei.suggest.SuggestionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VoiceImeService : InputMethodService(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: UserDictionaryRepository
    private lateinit var speechController: SpeechController

    private val suggestionEngine = SuggestionEngine()
    private val formatter = LocalFormatter()

    private var dictionaryEntries: List<DictionaryEntry> = emptyList()

    private var mode: ImeMode = ImeMode.SHORT
    private var isRecording = false
    private var manualKeyboardMode: ManualKeyboardMode = ManualKeyboardMode.TEXT

    private var lastCommittedText: String = ""
    private var lastSelectedCandidate: String = ""
    private var rawPreview: String = ""
    private var formattedPreview: String = ""

    private val appMemory = mutableMapOf<String, AppBuffer>()

    private var rootView: View? = null
    private var candidateContainer: LinearLayout? = null
    private var addWordButton: Button? = null
    private var micButton: Button? = null
    private var modeShortButton: Button? = null
    private var modeLongButton: Button? = null
    private var statusText: TextView? = null
    private var keyModeTextButton: Button? = null
    private var keyModeTenButton: Button? = null
    private var keyBackspaceButton: Button? = null
    private var textKeyboardPanel: LinearLayout? = null
    private var tenKeyPanel: LinearLayout? = null

    private var longPanel: LinearLayout? = null
    private var rawPreviewText: TextView? = null
    private var formattedPreviewText: TextView? = null
    private var bulletButton: Button? = null
    private var casualButton: Button? = null
    private var insertRawButton: Button? = null
    private var insertFormattedButton: Button? = null

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
        updateMicState()
        applyManualKeyboardMode()
        applyModeUI()
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
        stopRecordingIfNeeded()
    }

    override fun onDestroy() {
        stopRecordingIfNeeded()
        speechController.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onReady() {
        setStatus("録音中...")
    }

    override fun onPartial(text: String) {
        if (mode == ImeMode.LONG) {
            rawPreview = text
            rawPreviewText?.text = rawPreview
            setStatus("認識中(途中結果)")
        }
    }

    override fun onFinal(text: String) {
        val corrected = applyDictionaryCorrections(text.trim())
        if (corrected.isBlank()) {
            setStatus("認識結果が空でした")
            return
        }

        if (mode == ImeMode.SHORT) {
            commitAndTrack(corrected)
            setStatus("即入力しました")
        } else {
            rawPreview = corrected
            formattedPreview = ""
            rawPreviewText?.text = rawPreview
            formattedPreviewText?.text = ""
            setStatus("長文プレビューを更新")
        }
    }

    override fun onError(message: String) {
        setStatus(message)
        showToast(message)
    }

    override fun onEnd() {
        isRecording = false
        updateMicState()
    }

    private fun bindViews(view: View) {
        candidateContainer = view.findViewById(R.id.candidateContainer)
        addWordButton = view.findViewById(R.id.addWordButton)
        micButton = view.findViewById(R.id.micButton)
        modeShortButton = view.findViewById(R.id.modeShortButton)
        modeLongButton = view.findViewById(R.id.modeLongButton)
        statusText = view.findViewById(R.id.statusText)
        keyModeTextButton = view.findViewById(R.id.keyModeTextButton)
        keyModeTenButton = view.findViewById(R.id.keyModeTenButton)
        keyBackspaceButton = view.findViewById(R.id.keyBackspaceButton)
        textKeyboardPanel = view.findViewById(R.id.textKeyboardPanel)
        tenKeyPanel = view.findViewById(R.id.tenKeyPanel)

        longPanel = view.findViewById(R.id.longPanel)
        rawPreviewText = view.findViewById(R.id.rawPreviewText)
        formattedPreviewText = view.findViewById(R.id.formattedPreviewText)
        bulletButton = view.findViewById(R.id.bulletButton)
        casualButton = view.findViewById(R.id.casualButton)
        insertRawButton = view.findViewById(R.id.insertRawButton)
        insertFormattedButton = view.findViewById(R.id.insertFormattedButton)

        addWordPanel = view.findViewById(R.id.addWordPanel)
        addWordEdit = view.findViewById(R.id.addWordEdit)
        addReadingEdit = view.findViewById(R.id.addReadingEdit)
        saveWordButton = view.findViewById(R.id.saveWordButton)
        cancelWordButton = view.findViewById(R.id.cancelWordButton)
    }

    private fun setupListeners() {
        rootView?.let { bindTaggedKeyButtons(it) }

        addWordButton?.setOnClickListener {
            openAddWordPanel()
        }

        micButton?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRecordingIfAllowed()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopRecordingIfNeeded()
                    true
                }

                else -> false
            }
        }

        micButton?.setOnClickListener {
            if (isRecording) {
                stopRecordingIfNeeded()
            } else {
                startRecordingIfAllowed()
            }
        }

        modeShortButton?.setOnClickListener {
            mode = ImeMode.SHORT
            applyModeUI()
            refreshSuggestions()
        }

        modeLongButton?.setOnClickListener {
            mode = ImeMode.LONG
            applyModeUI()
            refreshSuggestions()
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

        bulletButton?.setOnClickListener {
            if (rawPreview.isBlank()) {
                setStatus("先に音声を認識してください")
                return@setOnClickListener
            }
            formattedPreview = formatter.toBulletPoints(
                input = rawPreview,
                dictionaryWords = dictionaryEntries.map { it.word }.toSet()
            )
            formattedPreviewText?.text = formattedPreview
            setStatus("箇条書きに整形")
        }

        casualButton?.setOnClickListener {
            if (rawPreview.isBlank()) {
                setStatus("先に音声を認識してください")
                return@setOnClickListener
            }
            formattedPreview = formatter.toCasualMessage(rawPreview)
            formattedPreviewText?.text = formattedPreview
            setStatus("送信用に整形")
        }

        insertRawButton?.setOnClickListener {
            if (rawPreview.isBlank()) return@setOnClickListener
            commitAndTrack(rawPreview)
            clearPreview()
            setStatus("原文を挿入")
        }

        insertFormattedButton?.setOnClickListener {
            val text = formattedPreview.ifBlank { rawPreview }
            if (text.isBlank()) return@setOnClickListener
            commitAndTrack(text)
            clearPreview()
            setStatus("整形結果を挿入")
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

        cancelWordButton?.setOnClickListener {
            hideAddWordPanel()
        }
    }

    private fun applyModeUI() {
        val isLong = mode == ImeMode.LONG
        longPanel?.isVisible = isLong

        modeShortButton?.alpha = if (!isLong) 1f else 0.65f
        modeLongButton?.alpha = if (isLong) 1f else 0.65f
    }

    private fun applyManualKeyboardMode() {
        val isText = manualKeyboardMode == ManualKeyboardMode.TEXT
        textKeyboardPanel?.isVisible = isText
        tenKeyPanel?.isVisible = !isText
        keyModeTextButton?.alpha = if (isText) 1f else 0.65f
        keyModeTenButton?.alpha = if (!isText) 1f else 0.65f
    }

    private fun bindTaggedKeyButtons(root: View) {
        if (root is Button) {
            val tag = root.tag as? String
            if (!tag.isNullOrBlank()) {
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

    private fun handleTaggedKeyInput(tag: String) {
        when (tag) {
            "SPACE" -> commitTextDirect(" ")
            "ENTER" -> commitTextDirect("\n")
            "BACKSPACE" -> handleBackspace()
            else -> commitTextDirect(tag)
        }
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
        refreshSuggestions()
    }

    private fun startRecordingIfAllowed() {
        if (isRecording) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            setStatus("マイク権限がありません")
            showToast("設定画面でマイク権限を許可してください")
            return
        }

        isRecording = true
        updateMicState()
        speechController.startListening()
    }

    private fun stopRecordingIfNeeded() {
        if (!isRecording) return
        speechController.stopListening()
        isRecording = false
        updateMicState()
    }

    private fun updateMicState() {
        val button = micButton ?: return
        if (isRecording) {
            button.text = "録音中"
            button.setBackgroundResource(R.drawable.bg_mic_active)
        } else {
            button.text = "押して録音"
            button.setBackgroundResource(R.drawable.bg_chip)
        }
    }

    private fun setStatus(message: String) {
        statusText?.text = message
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun clearPreview() {
        rawPreview = ""
        formattedPreview = ""
        rawPreviewText?.text = ""
        formattedPreviewText?.text = ""
    }

    private fun openAddWordPanel() {
        val seed = when {
            formattedPreview.isNotBlank() -> formattedPreview
            rawPreview.isNotBlank() -> rawPreview
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
            return phraseMap.entries
                .map { (text, stats) ->
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
}
