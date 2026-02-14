package com.toraonsei.dict

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toraonsei.R
import com.toraonsei.model.DictionaryEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DictionaryActivity : AppCompatActivity() {

    private lateinit var repository: UserDictionaryRepository

    private lateinit var wordInput: EditText
    private lateinit var readingInput: EditText
    private lateinit var priorityInput: EditText
    private lateinit var listView: ListView

    private val listItems = mutableListOf<String>()
    private var currentEntries: List<DictionaryEntry> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importFromUri(it) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        repository = UserDictionaryRepository(this)

        wordInput = findViewById(R.id.wordInput)
        readingInput = findViewById(R.id.readingInput)
        priorityInput = findViewById(R.id.priorityInput)
        listView = findViewById(R.id.dictionaryList)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listItems)
        listView.adapter = adapter

        findViewById<Button>(R.id.addButton).setOnClickListener {
            addOrUpdateFromForm()
        }
        findViewById<Button>(R.id.importButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            val fileName = "tora_dictionary_${System.currentTimeMillis()}.json"
            exportLauncher.launch(fileName)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            currentEntries.getOrNull(position)?.let { showEditDialog(it) }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            currentEntries.getOrNull(position)?.let { entry ->
                AlertDialog.Builder(this)
                    .setTitle("削除")
                    .setMessage("${entry.word} を削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        lifecycleScope.launch {
                            repository.remove(entry.word)
                            toast("削除しました")
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            true
        }

        lifecycleScope.launch {
            repository.entriesFlow.collectLatest { entries ->
                currentEntries = entries
                renderEntries(entries)
            }
        }
    }

    private fun renderEntries(entries: List<DictionaryEntry>) {
        listItems.clear()
        listItems.addAll(
            entries.map { entry ->
                "${entry.word} / よみ:${entry.readingKana} / p:${entry.priority}"
            }
        )
        adapter.notifyDataSetChanged()
    }

    private fun addOrUpdateFromForm() {
        val word = wordInput.text?.toString().orEmpty().trim()
        val reading = readingInput.text?.toString().orEmpty().trim()
        val priority = priorityInput.text?.toString()?.toIntOrNull() ?: 0

        if (word.isBlank() || reading.isBlank()) {
            toast("単語と読みは必須です")
            return
        }

        lifecycleScope.launch {
            repository.upsert(word, reading, priority)
            wordInput.text?.clear()
            readingInput.text?.clear()
            priorityInput.text?.clear()
            toast("保存しました")
        }
    }

    private fun showEditDialog(entry: DictionaryEntry) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_dictionary_entry, null)
        val wordEdit = view.findViewById<EditText>(R.id.dialogWordInput)
        val readingEdit = view.findViewById<EditText>(R.id.dialogReadingInput)
        val priorityEdit = view.findViewById<EditText>(R.id.dialogPriorityInput)

        wordEdit.setText(entry.word)
        readingEdit.setText(entry.readingKana)
        priorityEdit.setText(entry.priority.toString())

        AlertDialog.Builder(this)
            .setTitle("単語を編集")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val word = wordEdit.text?.toString().orEmpty().trim()
                val reading = readingEdit.text?.toString().orEmpty().trim()
                val priority = priorityEdit.text?.toString()?.toIntOrNull() ?: 0
                if (word.isBlank() || reading.isBlank()) {
                    toast("単語と読みは必須です")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    if (word != entry.word) {
                        repository.remove(entry.word)
                    }
                    repository.upsert(word, reading, priority)
                    toast("更新しました")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (raw.isNullOrBlank()) {
                toast("ファイルを読み込めませんでした")
                return@launch
            }
            try {
                val result = repository.importFromJson(raw)
                toast("${result.importedCount}件を取り込み")
            } catch (e: Exception) {
                toast("インポート失敗: ${e.message}")
            }
        }
    }

    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch {
            val entries = repository.getEntriesOnce()
            val json = repository.exportToJson(entries)
            val success = contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(json)
            } != null

            if (success) {
                toast("エクスポートしました")
            } else {
                toast("エクスポートに失敗しました")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
