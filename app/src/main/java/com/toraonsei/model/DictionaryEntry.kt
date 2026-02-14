package com.toraonsei.model

data class DictionaryEntry(
    val word: String,
    val readingKana: String,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
