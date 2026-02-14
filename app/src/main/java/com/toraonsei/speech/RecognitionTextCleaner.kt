package com.toraonsei.speech

class RecognitionTextCleaner {

    fun cleanPartial(input: String): String {
        return cleanCore(input, aggressive = false)
    }

    fun cleanFinal(input: String): String {
        return cleanCore(input, aggressive = true)
    }

    private fun cleanCore(input: String, aggressive: Boolean): String {
        if (input.isBlank()) return ""
        var out = input
            .replace(zeroWidthCharsRegex, "")
            .replace(fullWidthSpacesRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()

        if (out.isBlank()) return ""

        fillerRegexes.forEach { regex ->
            out = out.replace(regex, " ")
        }

        if (aggressive) {
            out = out
                .replace(noiseSymbolRegex, "")
                .replace(redundantPunctuationRegex, "$1")
                .replace(leadingTrailingNoiseRegex, "")
        }

        out = out
            .replace(whitespaceRegex, " ")
            .trim()

        return out
    }

    private companion object {
        val zeroWidthCharsRegex = Regex("[\\u200B-\\u200D\\uFEFF]")
        val fullWidthSpacesRegex = Regex("[\\u3000]+")
        val whitespaceRegex = Regex("\\s+")
        val fillerRegexes = listOf(
            Regex("(えー+|えっと|ええと|あの+|そのー+|うーん+|んー+|まー+|あー+)(?=[^ぁ-ゖァ-ヺー]|$)"),
            Regex("(まあ+|なんか+)(?=[^ぁ-ゖァ-ヺー]|$)")
        )
        val noiseSymbolRegex = Regex("[♪♫★☆※]+")
        val redundantPunctuationRegex = Regex("([。！？!?、,])\\1{1,}")
        val leadingTrailingNoiseRegex = Regex("^[、,。.!?！？]+|[、,。.!?！？]+$")
    }
}
