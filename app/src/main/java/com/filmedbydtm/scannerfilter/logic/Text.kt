package com.filmedbydtm.scannerfilter.logic

object Text {

    private val NON_WORD = Regex("[^a-z0-9' ]+")
    private val SPACES = Regex("\\s+")

    /** Lower-case, turn "&" into "and", strip punctuation, collapse whitespace. */
    fun normalize(s: String): String = s.lowercase()
        .replace("&", " and ")
        .replace(NON_WORD, " ")
        .replace(SPACES, " ")
        .trim()

    /** Whole-word / whole-phrase match. [haystack] must already be normalized. */
    fun containsPhrase(haystack: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        return " $haystack ".contains(" $phrase ")
    }

    val SINGLE_DIGITS = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "niner" to 9,
    )

    val NUMBER_WORDS: Set<String> = SINGLE_DIGITS.keys + setOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
        "eighty", "ninety", "hundred", "thousand", "double", "triple",
    )

    val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
    )

    val ORDINALS = setOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth",
    )

    /** "oh" / "o" only count as digits when next to another number word. */
    private val AMBIGUOUS_DIGITS = setOf("oh", "o")

    fun isNumberToken(word: String): Boolean =
        word.any { it.isDigit() } || (word in NUMBER_WORDS && word !in AMBIGUOUS_DIGITS)

    /**
     * Marks every word that is part of a spoken number ("one nine four", "twelve thousand",
     * "3 1 3"). "oh"/"o" are included only when they sit next to a real number word.
     */
    fun numberMask(words: List<String>): BooleanArray {
        val mask = BooleanArray(words.size) { isNumberToken(words[it]) }
        var changed = true
        while (changed) {
            changed = false
            for (i in words.indices) {
                if (!mask[i] && words[i] in AMBIGUOUS_DIGITS &&
                    ((i > 0 && mask[i - 1]) || (i + 1 < words.size && mask[i + 1]))
                ) {
                    mask[i] = true
                    changed = true
                }
            }
        }
        return mask
    }

    /**
     * True if the words contain something that sounds like a phone number:
     * a run of 7+ consecutive number words, or a 7+ digit written number.
     */
    fun looksLikePhoneNumber(words: List<String>): Boolean {
        if (words.any { w -> w.count { it.isDigit() } >= 7 }) return true
        val mask = numberMask(words)
        var run = 0
        var digits = 0
        for (i in words.indices) {
            if (mask[i]) {
                run++
                digits += words[i].count { it.isDigit() }.coerceAtLeast(1)
                if (run >= 7 || digits >= 7) return true
            } else {
                run = 0
                digits = 0
            }
        }
        return false
    }
}
