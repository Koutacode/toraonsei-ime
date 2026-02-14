package com.toraonsei.engine

enum class UsageSceneMode(
    val configValue: String,
    val label: String
) {
    WORK("work", "仕事"),
    MESSAGE("message", "メッセージ");

    fun next(): UsageSceneMode {
        return when (this) {
            WORK -> MESSAGE
            MESSAGE -> WORK
        }
    }

    companion object {
        fun fromConfig(value: String): UsageSceneMode {
            return values().firstOrNull { it.configValue == value } ?: MESSAGE
        }
    }
}
