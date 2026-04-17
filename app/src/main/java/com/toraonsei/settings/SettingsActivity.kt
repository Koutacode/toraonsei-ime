package com.toraonsei.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.toraonsei.R
import com.toraonsei.dict.DictionaryActivity
import com.toraonsei.engine.UsageSceneMode
import com.toraonsei.floating.FloatingVoiceService
import com.toraonsei.floating.TextInjectionAccessibilityService
import com.toraonsei.format.LocalLlmSupport
import com.toraonsei.lock.PasscodeLockActivity
import com.toraonsei.speech.SherpaAsrModelSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private data class RemoteModelFile(
        val fileName: String,
        val url: String
    )

    private companion object {
        private const val asrDownloadUserAgent = "ToraOnsei/1.0 ASRInstaller"
        private const val llmDownloadUserAgent = "ToraOnsei/1.0 LLMInstaller"
        private const val modelConnectTimeoutMs = 35_000
        private const val modelReadTimeoutMs = 120_000

        private val defaultLlmModelFile = RemoteModelFile(
            fileName = "model.gguf",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true"
        )
        private const val defaultLlmModelDisplayName = "Gemma 2 2B Instruct (Q4_K_M, 約1.6GB)"
    }

    private lateinit var configRepository: AppConfigRepository

    private lateinit var requestPermissionButton: Button
    private lateinit var openOverlayPermissionButton: Button
    private lateinit var openAccessibilitySettingsButton: Button
    private lateinit var sceneModeGroup: RadioGroup
    private lateinit var sceneModeMessage: RadioButton
    private lateinit var sceneModeWork: RadioButton
    private lateinit var localLlmStatusText: TextView
    private lateinit var localLlmPathText: TextView
    private lateinit var importLlmModelButton: Button
    private lateinit var asrModelStatusText: TextView
    private lateinit var importAsrModelButton: Button
    private lateinit var openDictionaryButton: Button
    private lateinit var floatingStatusText: TextView
    private lateinit var startFloatingButton: Button
    private lateinit var stopFloatingButton: Button
    private lateinit var lockButton: Button

    private var isDownloading = false
    private var suppressSceneSwitch = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (audioOk) toast("権限を許可しました") else toast("マイク権限がないと録音できません")
    }

    private val pickAsrTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) startAsrInstallFromTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepository = AppConfigRepository(applicationContext)

        lifecycleScope.launch {
            val config = configRepository.configFlow.first()
            if (!config.unlocked) {
                startActivity(Intent(this@SettingsActivity, PasscodeLockActivity::class.java))
                finish()
                return@launch
            }
            setContentView(R.layout.activity_settings)
            bindViews()
            wireListeners()
            observeConfig()
            maybePromptInitialModelDownload()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::localLlmStatusText.isInitialized) {
            updateLocalLlmStatus()
            updateAsrStatus()
            updateFloatingStatus()
        }
    }

    private fun bindViews() {
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        openOverlayPermissionButton = findViewById(R.id.openOverlayPermissionButton)
        openAccessibilitySettingsButton = findViewById(R.id.openAccessibilitySettingsButton)
        sceneModeGroup = findViewById(R.id.sceneModeGroup)
        sceneModeMessage = findViewById(R.id.sceneModeMessage)
        sceneModeWork = findViewById(R.id.sceneModeWork)
        localLlmStatusText = findViewById(R.id.localLlmStatusText)
        localLlmPathText = findViewById(R.id.localLlmPathText)
        importLlmModelButton = findViewById(R.id.importLlmModelButton)
        asrModelStatusText = findViewById(R.id.asrModelStatusText)
        importAsrModelButton = findViewById(R.id.importAsrModelButton)
        openDictionaryButton = findViewById(R.id.openDictionaryButton)
        floatingStatusText = findViewById(R.id.floatingStatusText)
        startFloatingButton = findViewById(R.id.startFloatingButton)
        stopFloatingButton = findViewById(R.id.stopFloatingButton)
        lockButton = findViewById(R.id.lockButton)
    }

    private fun wireListeners() {
        requestPermissionButton.setOnClickListener { requestMicrophoneAndNotification() }

        openOverlayPermissionButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                toast("オーバーレイ権限は許可済みです")
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        openAccessibilitySettingsButton.setOnClickListener {
            toast("「トラ音声入力」を有効にしてください")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        sceneModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSceneSwitch) return@setOnCheckedChangeListener
            val mode = if (checkedId == R.id.sceneModeWork) UsageSceneMode.WORK else UsageSceneMode.MESSAGE
            lifecycleScope.launch {
                configRepository.setUsageSceneMode(mode.configValue)
            }
        }

        importLlmModelButton.setOnClickListener { startLlmDownload() }
        importAsrModelButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sherpa-ONNX ASR モデル")
                .setMessage(
                    "Hugging Faceの Sense Voice 日本語モデルを端末内に配置します。" +
                        "\nダウンロードするか、手元のフォルダを選択できます。"
                )
                .setPositiveButton("自動ダウンロード") { _, _ -> startAsrAutoDownload() }
                .setNeutralButton("フォルダ選択") { _, _ ->
                    pickAsrTreeLauncher.launch(null)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        openDictionaryButton.setOnClickListener {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        startFloatingButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("先にオーバーレイ権限を許可してください")
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                toast("先にマイク権限を許可してください")
                requestMicrophoneAndNotification()
                return@setOnClickListener
            }
            FloatingVoiceService.start(this)
            toast("フローティング音声入力を起動しました")
            updateFloatingStatus()
        }

        stopFloatingButton.setOnClickListener {
            FloatingVoiceService.stop(this)
            toast("停止しました")
            updateFloatingStatus()
        }

        lockButton.setOnClickListener {
            lifecycleScope.launch {
                configRepository.lock()
                startActivity(Intent(this@SettingsActivity, PasscodeLockActivity::class.java))
                finish()
            }
        }
    }

    private fun observeConfig() {
        lifecycleScope.launch {
            configRepository.configFlow.collectLatest { config ->
                suppressSceneSwitch = true
                val mode = UsageSceneMode.fromConfig(config.usageSceneMode)
                sceneModeMessage.isChecked = mode == UsageSceneMode.MESSAGE
                sceneModeWork.isChecked = mode == UsageSceneMode.WORK
                suppressSceneSwitch = false
                updateLocalLlmStatus()
                updateAsrStatus()
                updateFloatingStatus()
            }
        }
    }

    private fun updateLocalLlmStatus() {
        val status = LocalLlmSupport.detect(this)
        if (status.available) {
            localLlmStatusText.text = "状態: 設定済み（${formatBytes(status.modelBytes)}）"
            localLlmPathText.text = status.modelPath
            importLlmModelButton.text = "モデルを再取得"
        } else {
            localLlmStatusText.text = "状態: 未設定 — ${status.reason}"
            localLlmPathText.text = "配置先: files/local_llm/model.gguf"
            importLlmModelButton.text = "Gemma 2 2B を自動取得（Wi-Fi推奨）"
        }
    }

    private fun updateAsrStatus() {
        val sherpa = SherpaAsrModelSupport.detect(this)
        asrModelStatusText.text = if (sherpa.hasAny) {
            val rn = sherpa.fast?.name ?: sherpa.accurate?.name ?: "配置済み"
            "状態: Sherpa-ONNX 使用可 ($rn)"
        } else {
            "状態: システムASR使用中（Sherpa未配置）"
        }
    }

    private fun updateFloatingStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = TextInjectionAccessibilityService.isAvailable()
        val parts = mutableListOf<String>()
        if (!overlayOk) parts += "オーバーレイ権限が必要"
        if (!accessibilityOk) parts += "アクセシビリティ未有効"
        if (parts.isEmpty()) parts += "準備完了"
        floatingStatusText.text = "状態: ${parts.joinToString(" / ")}"
    }

    private fun maybePromptInitialModelDownload() {
        val status = LocalLlmSupport.detect(this)
        if (status.available) return
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean("llm_prompt_shown", false)) return

        AlertDialog.Builder(this)
            .setTitle("AIモデル未インストール")
            .setMessage(
                "音声整形のためAIモデル ($defaultLlmModelDisplayName) が必要です。" +
                    "\nWi-Fi接続を推奨します。ダウンロードしますか？"
            )
            .setPositiveButton("ダウンロード") { _, _ ->
                prefs.edit().putBoolean("llm_prompt_shown", true).apply()
                startLlmDownload()
            }
            .setNeutralButton("後で") { _, _ ->
                prefs.edit().putBoolean("llm_prompt_shown", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestMicrophoneAndNotification() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startLlmDownload() {
        if (isDownloading) {
            toast("ダウンロード実行中です")
            return
        }
        isDownloading = true
        importLlmModelButton.isEnabled = false
        lifecycleScope.launch {
            localLlmStatusText.text = "状態: ダウンロード開始..."
            localLlmPathText.text = "取得元: $defaultLlmModelDisplayName"
            val result = runCatching { installDefaultLlmModel() }
            result.onSuccess { bytes ->
                configRepository.setLlmModelUri("")
                toast("モデルを取得しました (${formatBytes(bytes)})")
            }.onFailure { error ->
                toast("取得失敗: ${error.message ?: "不明なエラー"}")
            }
            isDownloading = false
            importLlmModelButton.isEnabled = true
            updateLocalLlmStatus()
        }
    }

    private suspend fun installDefaultLlmModel(): Long = withContext(Dispatchers.IO) {
        val targetDir = File(filesDir, "local_llm").apply { mkdirs() }
        val targetFile = File(targetDir, defaultLlmModelFile.fileName)
        val bytes = downloadFileFromUrl(
            url = defaultLlmModelFile.url,
            output = targetFile,
            userAgent = llmDownloadUserAgent,
            onProgress = { downloaded, total ->
                val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                val text = if (pct >= 0) {
                    "状態: DL中 $pct% (${formatBytes(downloaded)} / ${formatBytes(total)})"
                } else {
                    "状態: DL中 (${formatBytes(downloaded)})"
                }
                runOnUiThread { localLlmStatusText.text = text }
            }
        )
        if (bytes <= 0L || targetFile.length() <= 0L) {
            error("LLMモデルが空です")
        }
        targetFile.length()
    }

    private fun startAsrAutoDownload() {
        if (isDownloading) {
            toast("ダウンロード実行中です")
            return
        }
        isDownloading = true
        importAsrModelButton.isEnabled = false
        lifecycleScope.launch {
            asrModelStatusText.text = "状態: ASRダウンロード開始..."
            val result = runCatching { installDefaultAsrModel() }
            result.onSuccess { bytes ->
                toast("ASRモデルを配置しました (${formatBytes(bytes)})")
            }.onFailure { error ->
                toast("ASR取得失敗: ${error.message ?: "不明なエラー"}")
            }
            isDownloading = false
            importAsrModelButton.isEnabled = true
            updateAsrStatus()
        }
    }

    private fun startAsrInstallFromTree(uri: Uri) {
        if (isDownloading) {
            toast("処理中です")
            return
        }
        isDownloading = true
        importAsrModelButton.isEnabled = false
        lifecycleScope.launch {
            asrModelStatusText.text = "状態: ASRモデル配置中..."
            val result = runCatching { importAsrModelFromTree(uri) }
            result.onSuccess { count ->
                toast("ASRモデルをコピーしました ($count ファイル)")
            }.onFailure { error ->
                toast("ASR配置失敗: ${error.message ?: "不明なエラー"}")
            }
            isDownloading = false
            importAsrModelButton.isEnabled = true
            updateAsrStatus()
        }
    }

    private val defaultAsrModelFiles = listOf(
        RemoteModelFile(
            fileName = "tokens.txt",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt?download=true"
        ),
        RemoteModelFile(
            fileName = "model-sense-voice.onnx",
            url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx?download=true"
        )
    )

    private suspend fun installDefaultAsrModel(): Long = withContext(Dispatchers.IO) {
        val baseDir = SherpaAsrModelSupport.ensureBaseDir(this@SettingsActivity)
        val staging = File(baseDir, "_staging").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var total = 0L
        defaultAsrModelFiles.forEach { remote ->
            val target = File(staging, remote.fileName)
            total += downloadFileFromUrl(
                url = remote.url,
                output = target,
                userAgent = asrDownloadUserAgent,
                onProgress = { downloaded, size ->
                    val pct = if (size > 0) (downloaded * 100 / size).toInt() else -1
                    val msg = if (pct >= 0) {
                        "状態: ${remote.fileName} $pct%"
                    } else {
                        "状態: ${remote.fileName} DL中"
                    }
                    runOnUiThread { asrModelStatusText.text = msg }
                }
            )
        }

        val tokens = File(staging, "tokens.txt")
        val hasOnnx = staging.listFiles()?.any {
            it.isFile && it.name.lowercase(Locale.US).endsWith(".onnx")
        } ?: false
        if (!tokens.exists() || !hasOnnx) {
            staging.deleteRecursively()
            error("ASRモデル取得に失敗しました")
        }

        val fast = File(baseDir, "fast")
        if (fast.exists()) fast.deleteRecursively()
        if (!staging.renameTo(fast)) {
            fast.mkdirs()
            staging.listFiles()?.forEach { it.copyTo(File(fast, it.name), overwrite = true) }
            staging.deleteRecursively()
        }
        listOf("accurate", "second_pass", "offline").forEach { name ->
            File(baseDir, name).takeIf { it.exists() }?.deleteRecursively()
        }

        val size = fast.listFiles().orEmpty().sumOf { it.length() }
        if (size <= 0L) error("ASRモデル配置後のサイズが0です")
        size
    }

    private suspend fun importAsrModelFromTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val root = DocumentFile.fromTreeUri(this@SettingsActivity, treeUri)
            ?: error("フォルダを開けません")

        val modelFiles = mutableListOf<DocumentFile>()
        collectAsrModelFiles(root, modelFiles)
        val filtered = modelFiles
            .filter {
                val n = it.name?.lowercase(Locale.US).orEmpty()
                n.endsWith(".onnx") || n == "tokens.txt"
            }
            .distinctBy { it.uri.toString() }

        val hasTokens = filtered.any { it.name.equals("tokens.txt", ignoreCase = true) }
        val hasOnnx = filtered.any { it.name?.lowercase(Locale.US)?.endsWith(".onnx") == true }
        if (!hasTokens || !hasOnnx) {
            error("tokens.txt と .onnx を含むフォルダを選択してください")
        }

        val baseDir = SherpaAsrModelSupport.ensureBaseDir(this@SettingsActivity)
        val fast = File(baseDir, "fast").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var copied = 0
        filtered.forEach { file ->
            val safeName = sanitizeFileName(file.name ?: return@forEach)
            val target = File(fast, safeName)
            contentResolver.openInputStream(file.uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.length() > 0L) copied += 1
        }
        copied
    }

    private fun collectAsrModelFiles(root: DocumentFile, out: MutableList<DocumentFile>) {
        root.listFiles().forEach { file ->
            when {
                file.isDirectory -> collectAsrModelFiles(file, out)
                file.isFile -> out += file
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").ifBlank { "model.bin" }
    }

    private fun downloadFileFromUrl(
        url: String,
        output: File,
        userAgent: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Long {
        val parent = output.parentFile ?: error("保存先フォルダを作成できません")
        if (!parent.exists() && !parent.mkdirs()) {
            error("保存先フォルダを作成できません: ${parent.absolutePath}")
        }
        val tempFile = File(parent, "${output.name}.download").apply {
            if (exists()) delete()
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = modelConnectTimeoutMs
            readTimeout = modelReadTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val contentLength = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (onProgress != null && downloaded - lastReport >= 512 * 1024) {
                            onProgress(downloaded, contentLength)
                            lastReport = downloaded
                        }
                    }
                    onProgress?.invoke(downloaded, contentLength)
                }
            }
            if (tempFile.length() <= 0L) error("ダウンロードサイズが0")
            if (output.exists() && !output.delete()) error("既存ファイルを置換できません: ${output.name}")
            if (!tempFile.renameTo(output)) {
                tempFile.copyTo(output, overwrite = true)
                tempFile.delete()
            }
            return output.length()
        } catch (error: Exception) {
            runCatching { tempFile.delete() }
            throw IllegalStateException(
                "ダウンロード失敗 (${output.name}): ${error.message ?: "接続エラー"}"
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
