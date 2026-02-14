package com.toraonsei.dict

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DictionaryUpdater(private val context: Context) {

    suspend fun updateNow(maxCandidatesPerReading: Int = 8): Result = withContext(Dispatchers.IO) {
        require(maxCandidatesPerReading in 1..20) {
            "maxCandidatesPerReading must be between 1 and 20"
        }
        ensureInternetPermission()

        val tempDir = File(context.cacheDir, "dict_update_tmp").apply {
            if (exists()) {
                deleteRecursively()
            }
            mkdirs()
        }

        try {
            val downloaded = mutableListOf<Pair<String, File>>()
            val failedSources = mutableListOf<String>()
            val failedDetails = mutableListOf<String>()

            sourceFiles.forEach { fileName ->
                try {
                    downloaded += fileName to downloadFile(fileName, tempDir)
                } catch (e: Exception) {
                    failedSources += fileName
                    failedDetails += "$fileName: ${e.message ?: "不明なエラー"}"
                }
            }
            if (downloaded.isEmpty()) {
                val detailPreview = failedDetails.take(4).joinToString(" / ")
                throw IllegalStateException(
                    buildString {
                        append("辞書ダウンロード失敗: 取得できるSKK辞書がありません")
                        if (detailPreview.isNotBlank()) {
                            append("（")
                            append(detailPreview)
                            append("）")
                        }
                    }
                )
            }

            val mapping = buildMapping(downloaded.map { it.second }, maxCandidatesPerReading)
            val sourceLabels = downloaded.map { it.first }.toMutableList()

            val japanPostLoaded = try {
                val japanPostZip = downloadFileByUrl(
                    fileUrl = japanPostKenAllUrl,
                    outputName = japanPostZipName,
                    dir = tempDir
                )
                mergeJapanPostDictionary(
                    zipFile = japanPostZip,
                    mapping = mapping,
                    maxCandidatesPerReading = maxCandidatesPerReading
                )
                true
            } catch (_: Exception) {
                false
            }
            if (japanPostLoaded) {
                sourceLabels += "JapanPost:$japanPostZipName"
            } else {
                failedSources += "JapanPost:$japanPostZipName"
            }

            val output = LocalKanaKanjiDictionary(context).dynamicDictionaryFile()
            writeTsv(mapping, output, sourceLabels)

            Result(
                entries = mapping.size,
                bytes = output.length(),
                outputPath = output.absolutePath,
                sourceFiles = sourceLabels,
                failedSources = failedSources
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun downloadFile(fileName: String, dir: File): File {
        return downloadFileByUrl(
            fileUrl = "$sourceBaseUrl$fileName",
            outputName = fileName,
            dir = dir
        )
    }

    private fun downloadFileByUrl(fileUrl: String, outputName: String, dir: File): File {
        val output = File(dir, outputName)
        val url = URL(fileUrl)
        val conn = (url.openConnection() as HttpURLConnection)
        conn.apply {
            connectTimeout = 35_000
            readTimeout = 35_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ToraOnsei/1.0 DictionaryUpdater")
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("辞書ダウンロード失敗: $outputName (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                FileOutputStream(output).use { out ->
                    input.copyTo(out)
                }
            }
            return output
        } catch (e: Exception) {
            throw IllegalStateException(
                "辞書ダウンロード失敗: $outputName (${e.message ?: "接続エラー"})"
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun mergeJapanPostDictionary(
        zipFile: File,
        mapping: LinkedHashMap<String, MutableList<String>>,
        maxCandidatesPerReading: Int
    ) {
        var csvFound = false
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    parseJapanPostCsv(zip) { reading, candidate ->
                        addMappingCandidate(
                            mapping = mapping,
                            reading = reading,
                            candidate = candidate,
                            maxCandidatesPerReading = maxCandidatesPerReading
                        )
                    }
                    csvFound = true
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!csvFound) {
            throw IllegalStateException("日本郵便辞書のCSVが見つかりませんでした")
        }
    }

    private fun parseJapanPostCsv(
        inputStream: java.io.InputStream,
        onEntry: (reading: String, candidate: String) -> Unit
    ) {
        inputStream.bufferedReader(japanPostCharset).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                val fields = parseCsvLine(line)
                if (fields.size < 9) return@forEach

                val prefKana = normalizeJapanPostKana(fields[3])
                val cityKana = normalizeJapanPostKana(fields[4])
                val townKana = normalizeJapanPostKana(fields[5])
                val prefName = normalizeJapanPostName(fields[6])
                val cityName = normalizeJapanPostName(fields[7])
                val townName = normalizeJapanPostName(fields[8])

                if (prefKana.isNotBlank() && prefName.isNotBlank()) {
                    onEntry(prefKana, prefName)
                }
                if (cityKana.isNotBlank() && cityName.isNotBlank()) {
                    onEntry(cityKana, cityName)
                }
                if (prefKana.isNotBlank() && cityKana.isNotBlank() && prefName.isNotBlank() && cityName.isNotBlank()) {
                    onEntry(prefKana + cityKana, prefName + cityName)
                }
                if (townKana.isNotBlank() && townName.isNotBlank()) {
                    onEntry(townKana, townName)
                }
                if (cityKana.isNotBlank() && townKana.isNotBlank() && cityName.isNotBlank() && townName.isNotBlank()) {
                    onEntry(cityKana + townKana, cityName + townName)
                }
                if (
                    prefKana.isNotBlank() &&
                    cityKana.isNotBlank() &&
                    townKana.isNotBlank() &&
                    prefName.isNotBlank() &&
                    cityName.isNotBlank() &&
                    townName.isNotBlank()
                ) {
                    onEntry(prefKana + cityKana + townKana, prefName + cityName + townName)
                }
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i += 1
        }
        result.add(current.toString())
        return result
    }

    private fun normalizeJapanPostKana(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(" ", "")
            .replace("　", "")
            .replace("･", "")
            .replace("・", "")
            .replace(parenthesesRegex, "")
            .trim()

        if (normalized.isBlank()) return ""
        if (normalized in japanPostSkipKanaWords) return ""

        val hira = katakanaToHiragana(normalized)
        if (!kanaKeyRegex.matches(hira)) return ""
        return hira
    }

    private fun normalizeJapanPostName(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = raw
            .replace(" ", "")
            .replace("　", "")
            .replace(parenthesesRegex, "")
            .trim()
        if (normalized.isBlank()) return ""
        if (normalized in japanPostSkipNameWords) return ""
        if (!contentRegex.containsMatchIn(normalized)) return ""
        if (normalized.length > 48) return ""
        return normalized
    }

    private fun addMappingCandidate(
        mapping: LinkedHashMap<String, MutableList<String>>,
        reading: String,
        candidate: String,
        maxCandidatesPerReading: Int
    ) {
        if (reading.isBlank() || candidate.isBlank()) return
        if (!kanaKeyRegex.matches(reading)) return
        val list = mapping.getOrPut(reading) { mutableListOf() }
        if (list.size >= maxCandidatesPerReading) return
        if (candidate !in list) {
            list.add(candidate)
        }
    }

    private fun buildMapping(
        sourceGzipFiles: List<File>,
        maxCandidatesPerReading: Int
    ): LinkedHashMap<String, MutableList<String>> {
        val mapping = LinkedHashMap<String, MutableList<String>>()
        sourceGzipFiles.forEach { file ->
            parseDictionary(file) { key, candidate ->
                val list = mapping.getOrPut(key) { mutableListOf() }
                if (list.size >= maxCandidatesPerReading) {
                    return@parseDictionary
                }
                if (candidate !in list) {
                    list.add(candidate)
                }
            }
        }
        return mapping
    }

    private fun parseDictionary(
        gzipFile: File,
        onEntry: (reading: String, candidate: String) -> Unit
    ) {
        GZIPInputStream(gzipFile.inputStream()).bufferedReader(eucJpCharset).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith(";")) return@forEach
                val match = entryRegex.matchEntire(line) ?: return@forEach

                var key = match.groupValues[1]
                if (key.length > 1 && okuriKeyRegex.matches(key)) {
                    key = key.dropLast(1)
                }
                key = katakanaToHiragana(key)
                if (!kanaKeyRegex.matches(key)) return@forEach

                val body = match.groupValues[2]
                for (part in body.split('/')) {
                    if (part.isBlank()) continue
                    val candidate = normalizeCandidate(part)
                    if (candidate.isBlank()) continue
                    if (candidate.length > 28) continue
                    if (candidate.startsWith("(") || candidate.startsWith("[") || candidate.startsWith("http")) {
                        continue
                    }
                    if (!contentRegex.containsMatchIn(candidate)) continue
                    onEntry(key, candidate)
                }
            }
        }
    }

    private fun normalizeCandidate(raw: String): String {
        return raw.substringBefore(';').trim()
    }

    private fun writeTsv(
        mapping: LinkedHashMap<String, MutableList<String>>,
        output: File,
        sourceLabels: List<String>
    ) {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine("# source: dictionaries (${sourceLabels.joinToString(", ")})")
            writer.appendLine("# generated_at: $generatedAt")
            writer.appendLine("# format: reading<TAB>candidate1|candidate2|...")
            mapping.keys.sorted().forEach { key ->
                val values = mapping[key].orEmpty()
                if (values.isNotEmpty()) {
                    writer.append(key)
                    writer.append('\t')
                    writer.append(values.joinToString("|"))
                    writer.appendLine()
                }
            }
        }
    }

    private fun katakanaToHiragana(text: String): String {
        val out = StringBuilder(text.length)
        text.forEach { ch ->
            val code = ch.code
            if (code in 0x30A1..0x30F6) {
                out.append((code - 0x60).toChar())
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    data class Result(
        val entries: Int,
        val bytes: Long,
        val outputPath: String,
        val sourceFiles: List<String>,
        val failedSources: List<String>
    )

    private fun ensureInternetPermission() {
        val granted = context.checkPermission(
            Manifest.permission.INTERNET,
            Process.myPid(),
            Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw IllegalStateException("INTERNET権限がありません。最新版APKへ更新してください")
        }
    }

    companion object {
        private const val sourceBaseUrl = "https://skk-dev.github.io/dict/"
        private const val japanPostKenAllUrl = "https://www.post.japanpost.jp/zipcode/dl/kogaki/zip/ken_all.zip"
        private const val japanPostZipName = "ken_all.zip"
        val sourceFiles = listOf(
            "SKK-JISYO.S.gz",
            "SKK-JISYO.M.gz",
            "SKK-JISYO.L.gz",
            "SKK-JISYO.fullname.gz",
            "SKK-JISYO.propernoun.gz",
            "SKK-JISYO.jinmei.gz",
            "SKK-JISYO.station.gz",
            "SKK-JISYO.ML.gz",
            "SKK-JISYO.assoc.gz",
            "SKK-JISYO.geo.gz",
            "SKK-JISYO.law.gz",
            "SKK-JISYO.okinawa.gz",
            "SKK-JISYO.china_taiwan.gz",
            "SKK-JISYO.wrong.gz",
            "SKK-JISYO.pubdic+.gz"
        )

        private val eucJpCharset = Charset.forName("EUC-JP")
        private val japanPostCharset = Charset.forName("MS932")
        private val entryRegex = Regex("^([^\\s]+)\\s+/(.+)/$")
        private val okuriKeyRegex = Regex("^[ぁ-ゖァ-ヺー]+[a-z]$")
        private val kanaKeyRegex = Regex("^[ぁ-ゖー]{1,24}$")
        private val contentRegex = Regex("[\\p{IsHan}\\p{InHiragana}\\p{InKatakana}A-Za-z0-9]")
        private val parenthesesRegex = Regex("[（(].*?[）)]")
        private val japanPostSkipKanaWords = setOf(
            "イカニケイサイガナイバアイ",
            "ツギノビルヲノゾク",
            "ツギノバアイヲノゾク"
        )
        private val japanPostSkipNameWords = setOf(
            "以下に掲載がない場合",
            "次のビルを除く",
            "次の場合を除く"
        )
    }
}
