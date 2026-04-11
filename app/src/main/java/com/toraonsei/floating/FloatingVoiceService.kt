package com.toraonsei.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import com.toraonsei.R
import com.toraonsei.engine.HybridLocalInferenceEngine
import com.toraonsei.engine.LocalLlmInferenceEngine
import com.toraonsei.engine.UsageSceneMode
import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalFormatter
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
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingVoiceService : Service(), SpeechController.Callback {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var speechController: SpeechController
    private lateinit var localLlmEngine: LocalLlmInferenceEngine
    private lateinit var appConfigRepository: AppConfigRepository

    private val formatter = LocalFormatter()
    private val inferenceEngine = HybridLocalInferenceEngine(formatter)
    private val textCleaner = RecognitionTextCleaner()

    private var overlayView: View? = null
    private var micButton: Button? = null
    private var bubbleContainer: LinearLayout? = null
    private var statusText: TextView? = null
    private var previewText: TextView? = null
    private var actionRow: LinearLayout? = null
    private var insertButton: Button? = null
    private var copyButton: Button? = null
    private var clearButton: Button? = null

    private var isRecording = false
    private var formattedResult = ""
    private var rawVoiceText = StringBuilder()
    private var formatJob: Job? = null
    private var usageSceneMode = UsageSceneMode.MESSAGE
    private var formatStrength = FormatStrength.NORMAL

    private var layoutParams: WindowManager.LayoutParams? = null
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isMoved = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        speechController = SpeechController(this, this)
        localLlmEngine = LocalLlmInferenceEngine(this)
        appConfigRepository = AppConfigRepository(this)

        serviceScope.launch {
            appConfigRepository.configFlow.collectLatest { config ->
                usageSceneMode = UsageSceneMode.fromConfig(config.usageSceneMode)
                formatStrength = FormatStrength.fromConfig(config.formatStrength)
            }
        }
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
        stopRecording()
        removeOverlay()
        speechController.destroy()
        localLlmEngine.release()
        formatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // region SpeechController.Callback

    override fun onReady() {
        updateStatus("録音中...")
    }

    override fun onPartial(text: String) {
        val cleaned = textCleaner.cleanPartial(text.trim())
        if (cleaned.isNotBlank()) {
            val preview = buildPreviewText(cleaned)
            showPreview(preview)
            updateStatus("認識中... (${preview.length}文字)")
        }
    }

    override fun onFinal(text: String, alternatives: List<String>) {
        val best = alternatives
            .map { textCleaner.cleanFinal(it.trim()) }
            .filter { it.isNotBlank() }
            .firstOrNull() ?: textCleaner.cleanFinal(text.trim())
        if (best.isBlank()) return

        appendVoiceSegment(best)
        showPreview(rawVoiceText.toString())
        updateStatus("録音中... (${rawVoiceText.length}文字)")
    }

    override fun onError(message: String) {
        if (isRecording) {
            updateStatus("再開中...")
        } else {
            updateStatus(message)
        }
    }

    override fun onEnd() {
        if (isRecording) {
            serviceScope.launch {
                delay(200)
                if (isRecording) {
                    speechController.startListening()
                }
            }
        } else if (rawVoiceText.isNotBlank()) {
            runFormatting()
        }
    }

    // endregion

    private fun startRecording() {
        isRecording = true
        rawVoiceText.setLength(0)
        formattedResult = ""
        showBubble(true)
        updateStatus("録音開始...")
        showPreview("")
        showActions(false)
        updateMicButtonState()
        speechController.startListening()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        updateMicButtonState()
        speechController.stopListening()
        if (rawVoiceText.isNotBlank()) {
            updateStatus("AI整形中...")
            runFormatting()
        } else {
            updateStatus("音声が検出されませんでした")
            serviceScope.launch {
                delay(2000)
                if (!isRecording && formattedResult.isBlank()) {
                    showBubble(false)
                }
            }
        }
    }

    private fun runFormatting() {
        formatJob?.cancel()
        formatJob = serviceScope.launch {
            updateStatus("AI整形中...")
            val raw = rawVoiceText.toString().trim()
            if (raw.isBlank()) {
                updateStatus("テキストが空です")
                return@launch
            }

            val formatted = withContext(Dispatchers.IO) {
                val request = HybridLocalInferenceEngine.Request(
                    input = raw,
                    beforeCursor = "",
                    afterCursor = "",
                    appHistory = "",
                    dictionaryWords = emptySet(),
                    sceneMode = usageSceneMode,
                    trigger = HybridLocalInferenceEngine.Trigger.VOICE_INPUT,
                    strength = formatStrength
                )
                inferenceEngine.infer(request)
            }

            val result = if (formatted.isNotBlank()) formatted else raw

            // 会話モードではLINE等向けに文末の「。」を除去
            formattedResult = if (usageSceneMode == UsageSceneMode.MESSAGE) {
                removeTrailingPeriod(result)
            } else {
                result
            }

            showPreview(formattedResult)
            showActions(true)
            updateStatus("整形完了 (${formattedResult.length}文字)")
        }
    }

    private fun removeTrailingPeriod(text: String): String {
        return text.trimEnd().let { trimmed ->
            if (trimmed.endsWith("。")) trimmed.dropLast(1) else trimmed
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

    private fun buildPreviewText(tail: String): String {
        val base = rawVoiceText.toString().trim()
        if (base.isBlank()) return tail
        if (tail.isBlank()) return base
        if (base.endsWith(tail)) return base
        return "$base $tail"
    }

    private fun insertText() {
        if (formattedResult.isBlank()) return
        val injected = TextInjectionAccessibilityService.injectText(formattedResult)
        if (injected) {
            showToast("テキストを挿入しました")
        } else {
            copyToClipboard(formattedResult)
            showToast("クリップボードにコピーしました（貼り付けてください）")
        }
        resetState()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("toraonsei", text))
    }

    private fun resetState() {
        formattedResult = ""
        rawVoiceText.setLength(0)
        showPreview("")
        showActions(false)
        updateStatus("待機中")
        serviceScope.launch {
            delay(1500)
            if (!isRecording && formattedResult.isBlank()) {
                showBubble(false)
            }
        }
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
        previewText = view.findViewById(R.id.floatingPreviewText)
        actionRow = view.findViewById(R.id.floatingActionRow)
        insertButton = view.findViewById(R.id.floatingInsertButton)
        copyButton = view.findViewById(R.id.floatingCopyButton)
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
            y = 200
        }
        layoutParams = params

        micButton?.setOnTouchListener { v, event ->
            handleMicTouch(v, event, params)
        }

        insertButton?.setOnClickListener { insertText() }
        copyButton?.setOnClickListener {
            if (formattedResult.isNotBlank()) {
                copyToClipboard(formattedResult)
                showToast("コピーしました")
                resetState()
            }
        }
        clearButton?.setOnClickListener { resetState() }

        windowManager.addView(view, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleMicTouch(v: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    isMoved = true
                }
                if (isMoved) {
                    params.x = initialX - dx.toInt()
                    params.y = initialY - dy.toInt()
                    overlayView?.let {
                        windowManager.updateViewLayout(it, params)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isMoved) {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                }
                return true
            }
        }
        return false
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        overlayView = null
        micButton = null
        bubbleContainer = null
        statusText = null
        previewText = null
        actionRow = null
        insertButton = null
        copyButton = null
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

    private fun showPreview(text: String) {
        previewText?.text = text
        previewText?.isVisible = text.isNotBlank()
    }

    private fun showActions(visible: Boolean) {
        actionRow?.isVisible = visible
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region Notification

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

    // endregion

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
