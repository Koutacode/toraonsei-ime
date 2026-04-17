package com.toraonsei.format

enum class FormatStrength(val configValue: String) {
    LIGHT("light"),
    NORMAL("normal"),
    STRONG("strong");

    companion object {
        fun fromConfig(value: String): FormatStrength {
            return values().firstOrNull { it.configValue == value } ?: NORMAL
        }
    }
}
