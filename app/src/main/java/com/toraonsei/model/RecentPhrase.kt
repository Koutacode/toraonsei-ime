package com.toraonsei.model

data class RecentPhrase(
    val text: String,
    val frequency: Int,
    val lastUsedAt: Long
)
