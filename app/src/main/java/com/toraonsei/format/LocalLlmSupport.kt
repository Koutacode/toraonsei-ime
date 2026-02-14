package com.toraonsei.format

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream

object LocalLlmSupport {

    data class Status(
        val available: Boolean,
        val modelPath: String,
        val reason: String,
        val modelBytes: Long = 0L
    )

    fun detect(context: Context): Status {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            return Status(
                available = false,
                modelPath = "",
                reason = "この端末ABIは未対応（arm64-v8aのみ）"
            )
        }
        val candidates = linkedSetOf<File>().apply {
            add(File(context.filesDir, "local_llm/model.gguf"))
            context.getExternalFilesDir(null)?.let { add(File(it, "local_llm/model.gguf")) }

            collectGgufCandidates(File(context.filesDir, "local_llm")).forEach { add(it) }
            context.getExternalFilesDir(null)?.let { ext ->
                collectGgufCandidates(File(ext, "local_llm")).forEach { add(it) }
            }
        }

        val existing = candidates
            .asSequence()
            .filter { it.exists() && it.isFile && it.length() > 0L }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.length() }
            )
            .firstOrNull()
        if (existing != null) {
            if (!hasGgufHeader(existing)) {
                return Status(
                    available = false,
                    modelPath = existing.absolutePath,
                    reason = "GGUFヘッダー不一致（model.ggufを確認してください）",
                    modelBytes = existing.length()
                )
            }
            return Status(
                available = true,
                modelPath = existing.absolutePath,
                reason = "モデルファイル検出",
                modelBytes = existing.length()
            )
        }
        return Status(
            available = false,
            modelPath = "",
            reason = "モデルファイル未配置"
        )
    }

    fun toModelUri(path: String): String {
        return when {
            path.startsWith("content://") -> path
            path.startsWith("file://") -> path
            else -> "file://$path"
        }
    }

    private fun hasGgufHeader(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read != 4) return false
                header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            }
        }.getOrDefault(false)
    }

    private fun collectGgufCandidates(baseDir: File, depth: Int = 0, maxDepth: Int = 3): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory || depth > maxDepth) return emptyList()
        val found = mutableListOf<File>()
        baseDir.listFiles()?.forEach { file ->
            when {
                file.isFile && file.name.endsWith(".gguf", ignoreCase = true) -> found += file
                file.isDirectory -> found += collectGgufCandidates(file, depth + 1, maxDepth)
            }
        }
        return found
    }
}
