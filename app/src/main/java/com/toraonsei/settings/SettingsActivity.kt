package com.toraonsei.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.toraonsei.R
import com.toraonsei.dict.DictionaryActivity
import com.toraonsei.dict.DictionaryMaintenanceActivity
import com.toraonsei.format.EnglishStyle
import com.toraonsei.format.FormatStrength
import com.toraonsei.format.LocalLlmSupport
import com.toraonsei.speech.SherpaAsrModelSupport
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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

        private val defaultAsrModelFiles = listOf(
            RemoteModelFile(
                fileName = "tokens.txt",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt?download=true"
            ),
            RemoteModelFile(
                fileName = "model-sense-voice-ja-int8.onnx",
                url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx?download=true"
            )
        )
        private val defaultLlmModelFile = RemoteModelFile(
            fileName = "model.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        )
    }

    private lateinit var configRepository: AppConfigRepository
    private var unlocked = false
    private var suppressPreferenceEvents = false
    private var suppressRecognitionNoiseFilterSwitchEvent = false
    private var isModelDownloadRunning = false

    private lateinit var lockStatusText: TextView
    private lateinit var localOnlyModeStatusText: TextView
    private lateinit var passcodeEdit: EditText
    private lateinit var unlockButton: Button
    private lateinit var lockButton: Button
    private lateinit var openImeSettingsButton: Button
    private lateinit var openImePickerButton: Button
    private lateinit var requestPermissionButton: Button
    private lateinit var openDictionaryButton: Button
    private lateinit var openDictionaryMaintenanceButton: Button
    private lateinit var importAsrModelButton: Button
    private lateinit var importLlmModelButton: Button
    private lateinit var formatStrengthSpinner: Spinner
    private lateinit var englishStyleSpinner: Spinner
    private lateinit var recognitionNoiseFilterSwitch: SwitchCompat
    private lateinit var recognitionNoiseFilterStatusText: TextView
    private lateinit var asrModelStatusText: TextView
    private lateinit var asrModelSecondPassText: TextView
    private lateinit var asrModelPathText: TextView
    private lateinit var localLlmStatusText: TextView
    private lateinit var localLlmPathText: TextView

    private val formatStrengthLabels = listOf("弱め", "標準", "強め")
    private val formatStrengthValues = listOf(
        FormatStrength.LIGHT.configValue,
        FormatStrength.NORMAL.configValue,
        FormatStrength.STRONG.configValue
    )
    private val englishStyleLabels = listOf("ナチュラル", "カジュアル", "フォーマル")
    private val englishStyleValues = listOf(
        EnglishStyle.NATURAL.configValue,
        EnglishStyle.CASUAL.configValue,
        EnglishStyle.FORMAL.configValue
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        when {
            audioGranted && notificationGranted -> {
                toast("マイク/通知権限を許可しました")
            }
            audioGranted -> {
                toast("マイク権限は許可済み。通知権限は未許可です")
            }
            else -> {
                toast("マイク権限がないと音声入力できません")
            }
        }
    }

    private val importLlmModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || !ensureUnlocked()) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                importLocalLlmModel(uri)
            }.onSuccess { bytes ->
                lifecycleScope.launch {
                    configRepository.setLlmModelUri(uri.toString())
                }
                updateLocalLlmStatus()
                toast("LLMモデルを設定しました (${formatBytes(bytes)})")
            }.onFailure { error ->
                toast("LLMモデル設定に失敗: ${error.message ?: "不明なエラー"}")
            }
        }
    }

    private val importAsrModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null || !ensureUnlocked()) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                importAsrModelFromTree(uri)
            }.onSuccess { copied ->
                lifecycleScope.launch {
                    configRepository.setAsrModelTreeUri(uri.toString())
                }
                updateAsrModelStatus()
                toast("ASRモデルを設定しました ($copied ファイル)")
            }.onFailure { error ->
                toast("ASRモデル設定に失敗: ${error.message ?: "不明なエラー"}")
            }
        }
    }

    private val importAsrFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty() || !ensureUnlocked()) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                importAsrModelFromUris(uris)
            }.onSuccess { copied ->
                lifecycleScope.launch {
                    configRepository.setAsrModelTreeUri("")
                }
                updateAsrModelStatus()
                toast("ASRモデルをファイル選択で設定しました ($copied ファイル)")
            }.onFailure { error ->
                toast("ASRファイル設定に失敗: ${error.message ?: "不明なエラー"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configRepository = AppConfigRepository(this)
        purgeUnusedLocalAsrModels()

        lockStatusText = findViewById(R.id.lockStatusText)
        localOnlyModeStatusText = findViewById(R.id.localOnlyModeStatusText)
        passcodeEdit = findViewById(R.id.passcodeEdit)
        unlockButton = findViewById(R.id.unlockButton)
        lockButton = findViewById(R.id.lockButton)
        openImeSettingsButton = findViewById(R.id.openImeSettingsButton)
        openImePickerButton = findViewById(R.id.openImePickerButton)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        openDictionaryButton = findViewById(R.id.openDictionaryButton)
        openDictionaryMaintenanceButton = findViewById(R.id.openDictionaryMaintenanceButton)
        importAsrModelButton = findViewById(R.id.importAsrModelButton)
        importLlmModelButton = findViewById(R.id.importLlmModelButton)
        formatStrengthSpinner = findViewById(R.id.formatStrengthSpinner)
        englishStyleSpinner = findViewById(R.id.englishStyleSpinner)
        recognitionNoiseFilterSwitch = findViewById(R.id.recognitionNoiseFilterSwitch)
        recognitionNoiseFilterStatusText = findViewById(R.id.recognitionNoiseFilterStatusText)
        asrModelStatusText = findViewById(R.id.asrModelStatusText)
        asrModelSecondPassText = findViewById(R.id.asrModelSecondPassText)
        asrModelPathText = findViewById(R.id.asrModelPathText)
        localLlmStatusText = findViewById(R.id.localLlmStatusText)
        localLlmPathText = findViewById(R.id.localLlmPathText)
        importAsrModelButton.text = "ローカルASR（現在未使用）"
        importLlmModelButton.text = "ローカルLLMを自動取得（1タップ）"

        localOnlyModeStatusText.text = "音声認識: システムASR固定（速度優先）"

        setupPreferenceSpinners()

        openImeSettingsButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        openImePickerButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        requestPermissionButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            ensureMicrophonePermission()
        }

        openDictionaryButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        openDictionaryMaintenanceButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            startActivity(Intent(this, DictionaryMaintenanceActivity::class.java))
        }

        importAsrModelButton.setOnClickListener {
            toast("ローカルASRは現在未使用です（システムASR固定）")
        }
        importAsrModelButton.setOnLongClickListener {
            toast("ローカルASRは現在未使用です（システムASR固定）")
            true
        }

        importLlmModelButton.setOnClickListener {
            if (!ensureUnlocked()) return@setOnClickListener
            lifecycleScope.launch {
                if (!tryBeginModelDownload()) return@launch
                localLlmStatusText.text = "状態: 自動取得中..."
                localLlmPathText.text = "取得元: ${defaultLlmModelFile.fileName}（数分かかる場合があります）"
                runCatching {
                    installDefaultLlmModel()
                }.onSuccess { bytes ->
                    configRepository.setLlmModelUri("")
                    updateLocalLlmStatus()
                    toast("LLMモデルを自動設定しました（${formatBytes(bytes)}）")
                }.onFailure { error ->
                    updateLocalLlmStatus()
                    toast("LLM自動取得に失敗: ${error.message ?: "不明なエラー"}")
                }
                endModelDownload()
            }
        }
        importLlmModelButton.setOnLongClickListener {
            if (!ensureUnlocked()) return@setOnLongClickListener true
            toast("長押し: 手動設定（ggufファイル選択）")
            importLlmModelLauncher.launch(arrayOf("*/*"))
            true
        }

        unlockButton.setOnClickListener {
            val passcode = passcodeEdit.text?.toString().orEmpty()
            lifecycleScope.launch {
                val ok = configRepository.unlockWithPasscode(passcode)
                if (ok) {
                    passcodeEdit.setText("")
                    toast("ロック解除しました")
                } else {
                    toast("パスワードが違います")
                }
            }
        }

        lockButton.setOnClickListener {
            lifecycleScope.launch {
                configRepository.lock()
                toast("再ロックしました")
            }
        }

        recognitionNoiseFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressRecognitionNoiseFilterSwitchEvent) return@setOnCheckedChangeListener
            if (!ensureUnlocked()) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                configRepository.setRecognitionNoiseFilterEnabled(isChecked)
                updateRecognitionNoiseFilterStatus(isChecked)
                toast(
                    if (isChecked) {
                        "認識テキスト補正を有効化しました"
                    } else {
                        "認識テキスト補正を無効化しました"
                    }
                )
            }
        }

        lifecycleScope.launch {
            configRepository.configFlow.collectLatest { config ->
                unlocked = config.unlocked
                lockStatusText.text = if (config.unlocked) {
                    "状態: 解除済み（この端末で利用可能）"
                } else {
                    "状態: ロック中（パスワード 0623 で解除）"
                }

                suppressRecognitionNoiseFilterSwitchEvent = true
                recognitionNoiseFilterSwitch.isChecked = config.recognitionNoiseFilterEnabled
                suppressRecognitionNoiseFilterSwitchEvent = false
                updateRecognitionNoiseFilterStatus(config.recognitionNoiseFilterEnabled)

                updateSpinnerSelections(config)
                updateAsrModelStatus()
                updateLocalLlmStatus()
                applyLockUi(config.unlocked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAsrModelStatus()
        updateLocalLlmStatus()
    }

    private fun applyLockUi(isUnlocked: Boolean) {
        val interactive = isUnlocked && !isModelDownloadRunning
        lockButton.isEnabled = isUnlocked
        openImeSettingsButton.isEnabled = interactive
        openImePickerButton.isEnabled = interactive
        requestPermissionButton.isEnabled = interactive
        openDictionaryButton.isEnabled = interactive
        openDictionaryMaintenanceButton.isEnabled = interactive
        importAsrModelButton.isEnabled = false
        importLlmModelButton.isEnabled = interactive
        formatStrengthSpinner.isEnabled = interactive
        englishStyleSpinner.isEnabled = interactive
        recognitionNoiseFilterSwitch.isEnabled = interactive
    }

    private fun updateRecognitionNoiseFilterStatus(enabled: Boolean) {
        recognitionNoiseFilterStatusText.text = if (enabled) {
            "認識補正: 有効（推奨）"
        } else {
            "認識補正: 無効"
        }
    }

    private fun updateLocalLlmStatus() {
        val status = LocalLlmSupport.detect(this)
        localLlmStatusText.text = if (status.available) {
            "状態: 設定済み（実行可能）"
        } else {
            "状態: 設定済み（内蔵整形エンジンを使用）"
        }
        localLlmPathText.text = if (status.modelPath.isNotBlank()) {
            "モデル: ${status.modelPath}\nサイズ: ${formatBytes(status.modelBytes)}"
        } else {
            "状態詳細: ${status.reason}\n配置先: files/local_llm/*.gguf または externalFiles/local_llm/*.gguf"
        }
    }

    private fun updateAsrModelStatus() {
        asrModelStatusText.text = "状態: 設定済み（システム音声認識を使用）"
        asrModelSecondPassText.text = "高精度再評価: システム認識では対象外"
        asrModelPathText.text = "ローカルASRは現在未使用です（速度優先方針）"
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

    private fun purgeUnusedLocalAsrModels() {
        runCatching {
            File(filesDir, "sherpa_asr").takeIf { it.exists() }?.deleteRecursively()
        }
    }

    private fun ensureUnlocked(): Boolean {
        if (unlocked) return true
        toast("先にパスワード0623で解除してください")
        return false
    }

    private fun ensureMicrophonePermission() {
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (audioGranted && notificationGranted) {
            toast("マイク/通知権限は許可済みです")
            return
        }

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun tryBeginModelDownload(): Boolean {
        if (isModelDownloadRunning) {
            toast("モデル取得中です。完了までお待ちください")
            return false
        }
        isModelDownloadRunning = true
        applyLockUi(unlocked)
        return true
    }

    private fun endModelDownload() {
        isModelDownloadRunning = false
        applyLockUi(unlocked)
    }

    private suspend fun installDefaultAsrModel(): Long = withContext(Dispatchers.IO) {
        val baseDir = SherpaAsrModelSupport.ensureBaseDir(this@SettingsActivity)
        val stagingDir = File(baseDir, "_fast_download").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        var downloadedBytes = 0L
        defaultAsrModelFiles.forEach { remote ->
            val target = File(stagingDir, remote.fileName)
            downloadedBytes += downloadFileFromUrl(
                url = remote.url,
                output = target,
                userAgent = asrDownloadUserAgent
            )
        }

        val tokens = File(stagingDir, "tokens.txt")
        val hasOnnx = stagingDir.listFiles()
            ?.any { it.isFile && it.name.lowercase(Locale.US).endsWith(".onnx") }
            ?: false
        if (!tokens.exists() || !hasOnnx) {
            error("ASRモデル取得に失敗しました（tokens/.onnx不足）")
        }

        val fastDir = File(baseDir, "fast")
        if (fastDir.exists()) {
            fastDir.deleteRecursively()
        }
        if (!stagingDir.renameTo(fastDir)) {
            fastDir.mkdirs()
            stagingDir.listFiles()?.forEach { file ->
                file.copyTo(File(fastDir, file.name), overwrite = true)
            }
            stagingDir.deleteRecursively()
        }

        // 単一モデル運用に統一し、過去の second pass 混在を避ける。
        listOf("accurate", "second_pass", "offline", "asr_accurate", "final").forEach { dirName ->
            File(baseDir, dirName).takeIf { it.exists() }?.deleteRecursively()
        }

        val totalBytes = fastDir.listFiles().orEmpty().sumOf { it.length() }
        if (totalBytes <= 0L) {
            error("ASRモデル配置後のサイズが0です")
        }
        downloadedBytes.coerceAtLeast(totalBytes)
    }

    private suspend fun installDefaultLlmModel(): Long = withContext(Dispatchers.IO) {
        val targetDir = File(filesDir, "local_llm").apply { mkdirs() }
        val targetFile = File(targetDir, defaultLlmModelFile.fileName)
        val bytes = downloadFileFromUrl(
            url = defaultLlmModelFile.url,
            output = targetFile,
            userAgent = llmDownloadUserAgent
        )
        if (bytes <= 0L || targetFile.length() <= 0L) {
            error("LLMモデルが空です")
        }
        targetFile.length()
    }

    private fun downloadFileFromUrl(
        url: String,
        output: File,
        userAgent: String
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
            if (conn.responseCode !in 200..299) {
                error("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
            if (tempFile.length() <= 0L) {
                error("ダウンロードサイズが0です")
            }
            if (output.exists() && !output.delete()) {
                error("既存ファイルを置換できません: ${output.name}")
            }
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

    private fun setupPreferenceSpinners() {
        formatStrengthSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            formatStrengthLabels
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        englishStyleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            englishStyleLabels
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        formatStrengthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressPreferenceEvents || !unlocked) return
                val value = formatStrengthValues.getOrNull(position) ?: return
                lifecycleScope.launch {
                    configRepository.setFormatStrength(value)
                    toast("文章整形の強さを保存しました: ${formatStrengthLabels[position]}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        englishStyleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressPreferenceEvents || !unlocked) return
                val value = englishStyleValues.getOrNull(position) ?: return
                lifecycleScope.launch {
                    configRepository.setEnglishStyle(value)
                    toast("英語翻訳スタイルを保存しました: ${englishStyleLabels[position]}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private suspend fun importLocalLlmModel(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val targetDir = File(filesDir, "local_llm").apply { mkdirs() }
        val targetFile = File(targetDir, "model.gguf")
        val input = contentResolver.openInputStream(uri)
            ?: error("選択したファイルを読み込めません")
        input.use { source ->
            FileOutputStream(targetFile).use { sink ->
                source.copyTo(sink)
            }
        }
        if (targetFile.length() <= 0L) {
            error("モデルファイルが空です")
        }
        targetFile.length()
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
            .filter { file ->
                val name = file.name?.lowercase(Locale.US).orEmpty()
                name.endsWith(".onnx") || name == "tokens.txt"
            }
            .distinctBy { it.uri.toString() }

        val hasTokens = filtered.any { it.name.equals("tokens.txt", ignoreCase = true) }
        val hasOnnx = filtered.any { it.name?.lowercase(Locale.US)?.endsWith(".onnx") == true }
        if (!hasTokens || !hasOnnx) {
            error("tokens.txt と .onnx を含むASRモデルフォルダを選択してください")
        }

        val baseDir = SherpaAsrModelSupport.ensureBaseDir(this@SettingsActivity)
        val fastDir = File(baseDir, "fast").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val usedNames = mutableSetOf<String>()
        var copiedCount = 0
        filtered.forEachIndexed { index, file ->
            val safeName = uniqueFileName(
                desired = sanitizeFileName(file.name ?: "model_$index.bin"),
                used = usedNames
            )
            val target = File(fastDir, safeName)
            val input = contentResolver.openInputStream(file.uri) ?: return@forEachIndexed
            input.use { source ->
                FileOutputStream(target).use { sink ->
                    source.copyTo(sink)
                }
            }
            if (target.length() > 0L) {
                copiedCount += 1
            } else {
                runCatching { target.delete() }
            }
        }

        if (copiedCount <= 0) {
            error("ASRモデルのコピーに失敗しました")
        }
        copiedCount
    }

    private suspend fun importAsrModelFromUris(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        data class SourceFile(val uri: Uri, val name: String)

        val sources = uris
            .asSequence()
            .mapNotNull { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val name = DocumentFile.fromSingleUri(this@SettingsActivity, uri)?.name
                    ?: uri.lastPathSegment
                    ?: "model.bin"
                SourceFile(uri = uri, name = sanitizeFileName(name))
            }
            .toList()
        if (sources.isEmpty()) {
            error("ASRファイルを選択してください")
        }

        val filtered = sources.filter { source ->
            val lower = source.name.lowercase(Locale.US)
            lower.endsWith(".onnx") || lower == "tokens.txt"
        }
        val hasTokens = filtered.any { it.name.equals("tokens.txt", ignoreCase = true) }
        val hasOnnx = filtered.any { it.name.lowercase(Locale.US).endsWith(".onnx") }
        if (!hasTokens || !hasOnnx) {
            error("tokens.txt と .onnx を同時に選択してください")
        }

        val baseDir = SherpaAsrModelSupport.ensureBaseDir(this@SettingsActivity)
        val fastDir = File(baseDir, "fast").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val usedNames = mutableSetOf<String>()
        var copiedCount = 0
        filtered.forEachIndexed { index, source ->
            val safeName = uniqueFileName(
                desired = sanitizeFileName(source.name.ifBlank { "model_$index.bin" }),
                used = usedNames
            )
            val target = File(fastDir, safeName)
            val input = contentResolver.openInputStream(source.uri) ?: return@forEachIndexed
            input.use { inStream ->
                FileOutputStream(target).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (target.length() > 0L) {
                copiedCount += 1
            } else {
                runCatching { target.delete() }
            }
        }

        if (copiedCount <= 0) {
            error("ASRモデルのコピーに失敗しました")
        }
        copiedCount
    }

    private fun collectAsrModelFiles(node: DocumentFile, output: MutableList<DocumentFile>, depth: Int = 0) {
        if (depth > 5) return
        if (node.isFile) {
            output += node
            return
        }
        if (!node.isDirectory) return
        node.listFiles().forEach { child ->
            collectAsrModelFiles(child, output, depth + 1)
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "model.bin" }
        return trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun uniqueFileName(desired: String, used: MutableSet<String>): String {
        if (used.add(desired)) return desired

        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var serial = 2
        while (true) {
            val candidate = "${base}_$serial$ext"
            if (used.add(candidate)) return candidate
            serial += 1
        }
    }

    private fun updateSpinnerSelections(config: AppConfigRepository.AppConfig) {
        suppressPreferenceEvents = true
        val strengthIndex = formatStrengthValues.indexOf(config.formatStrength).let { if (it >= 0) it else 1 }
        val englishIndex = englishStyleValues.indexOf(config.englishStyle).let { if (it >= 0) it else 0 }
        if (formatStrengthSpinner.selectedItemPosition != strengthIndex) {
            formatStrengthSpinner.setSelection(strengthIndex, false)
        }
        if (englishStyleSpinner.selectedItemPosition != englishIndex) {
            englishStyleSpinner.setSelection(englishIndex, false)
        }
        suppressPreferenceEvents = false
    }
}
