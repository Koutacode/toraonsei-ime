package com.toraonsei.dict

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toraonsei.R
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class DictionaryMaintenanceActivity : AppCompatActivity() {

    private lateinit var dictionary: LocalKanaKanjiDictionary
    private lateinit var updater: DictionaryUpdater

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var updateButton: Button
    private lateinit var clearButton: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary_maintenance)

        dictionary = LocalKanaKanjiDictionary(this)
        updater = DictionaryUpdater(this)

        statusText = findViewById(R.id.maintenanceStatusText)
        detailText = findViewById(R.id.maintenanceDetailText)
        updateButton = findViewById(R.id.runDictionaryUpdateButton)
        clearButton = findViewById(R.id.clearDynamicDictionaryButton)

        updateButton.setOnClickListener {
            if (running) return@setOnClickListener
            runUpdate()
        }
        clearButton.setOnClickListener {
            if (running) return@setOnClickListener
            clearDynamicDictionary()
        }

        refreshInfo("待機中")
    }

    private fun runUpdate() {
        running = true
        applyRunningState()
        statusText.text = "更新中…"
        detailText.text = "全辞書ソース（拡張SKK + 日本郵便）を一括ダウンロードして再構築しています"

        lifecycleScope.launch {
            try {
                val result = updater.updateNow()
                dictionary.reloadFromDisk()
                val sizeMb = DecimalFormat("0.00").format(result.bytes / 1024.0 / 1024.0)
                val failedLabel = if (result.failedSources.isNotEmpty()) {
                    "\n取得失敗: ${result.failedSources.joinToString(", ")}"
                } else {
                    ""
                }
                refreshInfo(
                    if (result.failedSources.isEmpty()) {
                        "更新完了: ${result.entries}件"
                    } else {
                        "更新完了（一部失敗あり）: ${result.entries}件"
                    },
                    "保存先: ${result.outputPath}\nサイズ: ${sizeMb}MB\n取得ソース: ${result.sourceFiles.joinToString(", ")}$failedLabel"
                )
                toast(
                    if (result.failedSources.isEmpty()) {
                        "辞書を更新しました"
                    } else {
                        "辞書更新完了（一部ソース取得失敗）"
                    }
                )
            } catch (e: Exception) {
                refreshInfo("更新失敗", e.message ?: "不明なエラー")
                toast("辞書更新に失敗: ${e.message}")
            } finally {
                running = false
                applyRunningState()
            }
        }
    }

    private fun clearDynamicDictionary() {
        val file = dictionary.dynamicDictionaryFile()
        val deleted = !file.exists() || file.delete()
        lifecycleScope.launch {
            dictionary.reloadFromDisk()
            if (deleted) {
                refreshInfo("同梱辞書に戻しました")
                toast("ローカル更新辞書を削除しました")
            } else {
                refreshInfo("削除失敗", "ファイルを削除できませんでした")
                toast("削除に失敗しました")
            }
        }
    }

    private fun refreshInfo(status: String, extra: String = "") {
        statusText.text = status
        val dynamicFile = dictionary.dynamicDictionaryFile()
        val timeLabel = if (dynamicFile.exists()) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date(dynamicFile.lastModified()))
            "最終更新: $time"
        } else {
            "最終更新: なし（同梱辞書のみ）"
        }
        detailText.text = buildString {
            append(timeLabel)
            if (extra.isNotBlank()) {
                append('\n')
                append(extra)
            }
            append("\n反映: キーボードを一度閉じて再表示してください")
        }
    }

    private fun applyRunningState() {
        updateButton.isEnabled = !running
        clearButton.isEnabled = !running
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
