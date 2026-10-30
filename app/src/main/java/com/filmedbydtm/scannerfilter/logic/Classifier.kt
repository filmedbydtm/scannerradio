package com.filmedbydtm.scannerfilter.logic

/** One recognized word with its timing (seconds from the start of the clip). */
data class Word(val text: String, val start: Double, val end: Double, val conf: Double = 1.0)

enum class Action { PLAY, REDACT, SKIP, IGNORE }

data class Decision(val action: Action, val category: String, val reason: String)

class Classifier(
    private val categories: List<Category>,
    private val minDurationSec: Double = 2.0,
    /** REDACT calls are dropped when recognition is too unsure to trust the edits. */
    private val minRedactConfidence: Double = 0.55,
) {

    fun classify(words: List<Word>, durationSec: Double): Decision {
        if (durationSec < minDurationSec) {
            return Decision(Action.SKIP, "Too short", "under %.1fs".format(minDurationSec))
        }
        val tokens = words.flatMap { Text.normalize(it.text).split(" ") }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Decision(Action.IGNORE, "No speech", "nothing recognized")
        val text = tokens.joinToString(" ")

        if (Text.looksLikePhoneNumber(tokens)) {
            return Decision(Action.SKIP, "Phone numbers", "long run of digits")
        }

        fun firstMatch(bucket: Bucket): Pair<Category, String>? {
            for (c in categories) {
                if (c.bucket != bucket) continue
                val hit = c.keywords.firstOrNull { Text.containsPhrase(text, it) }
                if (hit != null) return c to hit
            }
            return null
        }

        firstMatch(Bucket.NEVER)?.let { (c, kw) -> return Decision(Action.SKIP, c.name, "\"$kw\"") }
        firstMatch(Bucket.PLAY_HARD)?.let { (c, kw) -> return Decision(Action.PLAY, c.name, "\"$kw\"") }
        firstMatch(Bucket.MEDICAL)?.let { (c, kw) -> return Decision(Action.SKIP, c.name, "\"$kw\"") }
        firstMatch(Bucket.PLAY)?.let { (c, kw) -> return Decision(Action.PLAY, c.name, "\"$kw\"") }
        firstMatch(Bucket.REDACT)?.let { (c, kw) ->
            val avgConf = words.map { it.conf }.average()
            if (avgConf < minRedactConfidence) {
                return Decision(
                    Action.SKIP, c.name,
                    "audio too unclear to edit safely (%.0f%% confidence)".format(avgConf * 100),
                )
            }
            return Decision(Action.REDACT, c.name, "\"$kw\"")
        }
        return Decision(Action.IGNORE, "Other", "no matching category")
    }
}
