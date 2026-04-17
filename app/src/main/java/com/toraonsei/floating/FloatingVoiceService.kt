package com.toraonsei.floating

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import androidx.window.layout.FoldingFeature
import com.toraonsei.R
import com.toraonsei.dict.UserDictionaryRepository
import com.toraonsei.engine.LocalLlmInferenceEngine
import com.toraonsei.engine.TypelessFormatter
import com.toraonsei.engine.UsageSceneMode
import com.toraonsei.session.AppContextProvider
import com.toraonsei.settings.AppConfigRepository
import com.toraonsei.settings.SettingsActivity
import com.toraonsei.speech.RecognitionTextCleaner
import com.toraonsei.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingVoiceService : Service(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var speechController: SpeechController
    private lateinit var localLlmEngine: LocalLlmInferenceEngine
    private lateinit var userDictionary: UserDictionaryRepository
    private lateinit var typelessFormatter: TypelessFormatter
    private lateinit var appConfigRepository: AppConfigRepository

    private val textCleaner = RecognitionTextCleaner()

    private var overlayView: View? = null
    private var micButton: Button? = null
    private var bubbleContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var appContextText: TextView? = null
    private var previewEdit: EditText? = null
    private var actionRow: LinearLayout? = null
    private var insertButton: Button? = null
    private var copyButton: Button? = null
    private var undoButton: Button? = null
    private var clearButton: Button? = null

    private enum class RecordTrigger { NONE, TAP, LONG_PRESS }

    private var isRecording = false
    private var recordTrigger = RecordTrigger.NONE
    private var rawVoiceText = StringBuilder()
    private var formattedResult = ""
    private var lastInsertedText = ""
    private var formatJob: Job? = null
    private var usageSceneMode = UsageSceneMode.MESSAGE
    private var currentAppPackage: String? = null
    private var currentAppLabel: String? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var foldableController: FoldableLayoutController? = null
    private var lastFoldingFeature: FoldingFeature? = null
    private var lastWindowBounds: Rect = Rect()

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isMoved = false
    private var touchDownAtMs = 0L
    private var longPressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        speechController = SpeechController(this, this)
        localLlmEngine = LocalLlmInferenceEngine(this)
        userDictionary = UserDictionaryRepository(this)
        typelessFormatter = TypelessFormatter(localLlmEngine, userDictionary)
        appConfigRepository = AppConfigRepository(this)

        serviceScope.launch {
            appConfigRepository.configFlow.collectLatest { config ->
                usageSceneMode = UsageSceneMode.fromConfig(config.usageSceneMode)
            }
        }

        serviceScope.launch {
            AppContextProvider.focusedApp.collectLatest { app ->
                currentAppPackage = app?.packageName
                currentAppLabel = app?.label
                renderAppContext()
            }
        }

        foldableController = FoldableLayoutController(this, serviceScope) { folding, bounds ->
            lastFoldingFeature = folding
            lastWindowBounds = bounds
            adjustOverlayForFoldable()
        }.also { it.start() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustOverlayForFoldable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundWithNotification()
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecordingIfActive()
        longPressJob?.cancel()
        foldableController?.stop()
        foldableController = null
        removeOverlay()
        speechController.destroy()
        localLlmEngine.release()
        formatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun adjustOverlayForFoldable() {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val folding = lastFoldingFeature
        val bounds = lastWindowBounds.takeIf { !it.isEmpty } ?: return
        val bubblePx = (72 * resources.displayMetrics.density).toInt()

        if (folding != null && folding.isSeparating) {
            val overlaps = FoldableLayoutController.isBubbleOverHinge(params, bubblePx, bounds, folding)
            if (overlaps) {
                val hingeBottom = folding.bounds.bottom
                val marginPx = (24 * resources.displayMetrics.density).toInt()
                params.y = (bounds.bottom - hingeBottom + marginPx).coerceAtLeast(marginPx)
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    // region SpeechController.Callback

    override fun onReady() {
        updateStatus("録音中... 話しかけてください")
    }

    override fun onPartial(text: String) {
        val cleaned = textCleaner.cleanPartial(text.trim())
        if (cleaned.isBlank()) return
        val preview = buildCombinedPreview(cleaned)
        setPreview(preview)
        updateStatus("認識中... (${preview.length}文字)")
    }

    override fun onFinal(text: String, alternatives: List<String>) {
        val best = alternatives
            .map { textCleaner.cleanFinal(it.trim()) }
            .firstOrNull { it.isNotBlank() }
            ?: textCleaner.cleanFinal(text.trim())
        if (best.isBlank()) return

        appendVoiceSegment(best)
        setPreview(rawVoiceText.toString())
        updateStatus("録音中... (${rawVoiceText.length}文字)")
    }

    override fun onError(message: String) {
        updateStatus(if (isRecording) "再接続中..." else message)
    }

    override fun onEnd() {
        if (isRecording) {
            // 連続収集: 発話切れで自動再開
            serviceScope.launch {
                delay(180)
                if (isRecording) {
                    speechController.startListening(biasHints = collectBiasHints())
                }
            }
        } else if (rawVoiceText.isNotBlank()) {
            runFormatting()
        }
    }

    // endregion

    private fun startRecording(trigger: RecordTrigger) {
        if (isRecording) return
        isRecording = true
        recordTrigger = trigger
        rawVoiceText.setLength(0)
        formattedResult = ""
        showBubble(true)
        setPreview("")
        showActions(false)
        updateMicButtonState()
        updateStatus("録音開始...")
        speechController.startListening(biasHints = collectBiasHints())
    }

    private fun stopRecordingIfActive() {
        if (!isRecording) return
        isRecording = false
        recordTrigger = RecordTrigger.NONE
        updateMicButtonState()
        speechController.stopListening()
        if (rawVoiceText.isNotBlank()) {
            updateStatus("AI整形中...")
            runFormatting()
        } else {
            updateStatus("音声が検出されませんでした")
            serviceScope.launch {
                delay(1800)
                if (!isRecording && formattedResult.isBlank()) {
                    showBubble(false)
                }
            }
        }
    }

    private fun runFormatting() {
        formatJob?.cancel()
        formatJob = serviceScope.launch {
            val raw = rawVoiceText.toString().trim()
            if (raw.isBlank()) {
                updateStatus("テキストが空です")
                return@launch
            }
            updateStatus("AI整形中...")
            val result = typelessFormatter.format(
                TypelessFormatter.Request(
                    rawText = raw,
                    sceneMode = usageSceneMode,
                    appPackageName = currentAppPackage,
                    appLabel = currentAppLabel
                )
            )
            formattedResult = result.formatted
            setPreview(formattedResult)
            showActions(true)
            val indicator = if (result.usedLlm) "AI整形" else "素のテキスト"
            updateStatus("$indicator 完了 (${formattedResult.length}文字)")
        }
    }

    private fun appendVoiceSegment(segment: String) {
        val current = rawVoiceText.toString().trim()
        if (current.isBlank()) {
            rawVoiceText.setLength(0)
            rawVoiceText.append(segment)
            return
        }
        if (current.endsWith(segment) || current.contains(segment)) return
        if (segment.contains(current)) {
            rawVoiceText.setLength(0)
            rawVoiceText.append(segment)
            return
        }
        rawVoiceText.append(" ").append(segment)
    }

    private fun buildCombinedPreview(tail: String): String {
        val base = rawVoiceText.toString().trim()
        if (base.isBlank()) return tail
        if (tail.isBlank()) return base
        if (base.endsWith(tail)) return base
        return "$base $tail"
    }

    private fun collectBiasHints(): List<String> {
        val entries = runCatching { kotlinx.coroutines.runBlocking { userDictionary.getEntriesOnce() } }
            .getOrDefault(emptyList())
        val words = entries
            .asSequence()
            .filter { it.word.isNotBlank() }
            .sortedByDescending { it.priority }
            .map { it.word }
            .toList()
        val appLabel = currentAppLabel
        return if (appLabel.isNullOrBlank()) words else (words + appLabel)
    }

    private fun editedOrFormatted(): String {
        val edited = previewEdit?.text?.toString()?.trim().orEmpty()
        return edited.ifBlank { formattedResult }
    }

    private fun performInsert() {
        val text = editedOrFormatted()
        if (text.isBlank()) return
        val injected = TextInjectionAccessibilityService.injectText(text)
        if (injected) {
            lastInsertedText = text
            showToast("テキストを挿入しました")
        } else {
            copyToClipboard(text)
            lastInsertedText = text
            showToast("クリップボードにコピー（貼り付けてください）")
        }
        resetStateAfterSuccess()
    }

    private fun performCopy() {
        val text = editedOrFormatted()
        if (text.isBlank()) return
        copyToClipboard(text)
        lastInsertedText = text
        showToast("コピーしました")
        resetStateAfterSuccess()
    }

    private fun performUndo() {
        if (lastInsertedText.isBlank()) {
            showToast("戻せる履歴はありません")
            return
        }
        val cleared = TextInjectionAccessibilityService.injectText("")
        if (cleared) {
            showToast("直前の挿入を取り消しました")
        } else {
            showToast("自動で戻せません。手動で削除してください")
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("toraonsei", text))
    }

    private fun resetStateAfterSuccess() {
        formattedResult = ""
        rawVoiceText.setLength(0)
        setPreview("")
        showActions(false)
        updateStatus("待機中")
        serviceScope.launch {
            delay(1500)
            if (!isRecording && formattedResult.isBlank()) {
                showBubble(false)
            }
        }
    }

    private fun clearCurrentDraft() {
        formatJob?.cancel()
        formattedResult = ""
        rawVoiceText.setLength(0)
        setPreview("")
        showActions(false)
        updateStatus("消去しました")
    }

    // region Overlay UI

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.floating_voice_overlay, null)
        overlayView = view

        micButton = view.findViewById(R.id.floatingMicButton)
        bubbleContainer = view.findViewById(R.id.bubbleContainer)
        statusText = view.findViewById(R.id.floatingStatusText)
        appContextText = view.findViewById(R.id.floatingAppContextText)
        previewEdit = view.findViewById(R.id.floatingPreviewEdit)
        actionRow = view.findViewById(R.id.floatingActionRow)
        insertButton = view.findViewById(R.id.floatingInsertButton)
        copyButton = view.findViewById(R.id.floatingCopyButton)
        undoButton = view.findViewById(R.id.floatingUndoButton)
        clearButton = view.findViewById(R.id.floatingClearButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 220
        }
        layoutParams = params

        micButton?.setOnTouchListener { v, event -> handleMicTouch(v, event, params) }

        insertButton?.setOnClickListener { performInsert() }
        copyButton?.setOnClickListener { performCopy() }
        undoButton?.setOnClickListener { performUndo() }
        clearButton?.setOnClickListener { clearCurrentDraft() }

        windowManager.addView(view, params)
        renderAppContext()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleMicTouch(v: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isMoved = false
                touchDownAtMs = SystemClock.uptimeMillis()

                longPressJob?.cancel()
                longPressJob = serviceScope.launch {
                    delay(longPressMs)
                    if (!isMoved && !isRecording) {
                        startRecording(RecordTrigger.LONG_PRESS)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    if (!isMoved) {
                        longPressJob?.cancel()
                    }
                    isMoved = true
                }
                if (isMoved) {
                    params.x = (initialX - dx.toInt()).coerceAtLeast(0)
                    params.y = (initialY - dy.toInt()).coerceAtLeast(0)
                    overlayView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressJob?.cancel()
                if (isMoved) return true
                val elapsed = SystemClock.uptimeMillis() - touchDownAtMs
                when {
                    recordTrigger == RecordTrigger.LONG_PRESS -> stopRecordingIfActive()
                    elapsed >= longPressMs -> {
                        // すでに録音開始しているはず。安全策で止める
                        stopRecordingIfActive()
                    }
                    isRecording -> stopRecordingIfActive()
                    else -> startRecording(RecordTrigger.TAP)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                if (recordTrigger == RecordTrigger.LONG_PRESS && isRecording) {
                    stopRecordingIfActive()
                }
                return true
            }
        }
        return false
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        micButton = null
        bubbleContainer = null
        statusText = null
        appContextText = null
        previewEdit = null
        actionRow = null
        insertButton = null
        copyButton = null
        undoButton = null
        clearButton = null
    }

    private fun updateMicButtonState() {
        micButton?.let { btn ->
            if (isRecording) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
                btn.text = "⏹"
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF00897B.toInt())
                btn.text = "\uD83C\uDFA4"
            }
        }
    }

    private fun showBubble(visible: Boolean) {
        bubbleContainer?.isVisible = visible
    }

    private fun updateStatus(text: String) {
        statusText?.text = text
    }

    private fun setPreview(text: String) {
        val edit = previewEdit ?: return
        if (edit.text?.toString() != text) {
            edit.setText(text)
            edit.setSelection(edit.text?.length ?: 0)
        }
    }

    private fun showActions(visible: Boolean) {
        actionRow?.isVisible = visible
    }

    private fun renderAppContext() {
        val label = currentAppLabel
        val view = appContextText ?: return
        if (label.isNullOrBlank()) {
            view.isVisible = false
        } else {
            view.text = "対象: $label"
            view.isVisible = true
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    private fun startForegroundWithNotification() {
        val channelId = "floating_voice"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.floating_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FloatingVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val settingsIntent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPending = PendingIntent.getActivity(
            this, 1, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_tiger_launcher)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setContentIntent(settingsPending)
            .addAction(0, "停止", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.toraonsei.floating.ACTION_STOP"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, FloatingVoiceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
