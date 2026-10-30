package com.filmedbydtm.scannerfilter.logic

data class Span(val start: Double, val end: Double)

/**
 * Finds the parts of a clip to bleep: every spoken number (house numbers, years, etc.),
 * month names, ordinals near a month / "born", and the words after "date of birth" / "DOB".
 * Low-confidence words touching a bleeped word are bleeped too, since the recognizer may have
 * misheard part of a number.
 */
object Redactor {

    private const val PAD_SEC = 0.12
    private const val LOW_CONF = 0.5
    private val BIRTH_TRIGGERS = listOf(
        listOf("date", "of", "birth"), listOf("d", "o", "b"), listOf("dob"),
        listOf("born"), listOf("birthday"), listOf("birth", "date"),
    )
    /** How many words after a birth trigger are always bleeped. */
    private const val BIRTH_TAIL_WORDS = 6

    fun findSpans(words: List<Word>, clipDurationSec: Double): List<Span> {
        val tokens = words.map { Text.normalize(it.text) }
        val mask = Text.numberMask(tokens)

        for (i in tokens.indices) {
            if (tokens[i] in Text.MONTHS) mask[i] = true
        }
        for (i in tokens.indices) {
            if (tokens[i] in Text.ORDINALS) {
                val near = (maxOf(0, i - 2)..minOf(tokens.lastIndex, i + 2))
                    .any { tokens[it] in Text.MONTHS || tokens[it] == "born" }
                if (near) mask[i] = true
            }
        }
        for (i in tokens.indices) {
            for (trigger in BIRTH_TRIGGERS) {
                if (i + trigger.size <= tokens.size &&
                    trigger.indices.all { tokens[i + it] == trigger[it] }
                ) {
                    val from = i + trigger.size
                    for (j in from until minOf(tokens.size, from + BIRTH_TAIL_WORDS)) mask[j] = true
                }
            }
        }
        // Widen into neighbouring low-confidence words.
        var changed = true
        while (changed) {
            changed = false
            for (i in tokens.indices) {
                if (!mask[i] && words[i].conf < LOW_CONF &&
                    ((i > 0 && mask[i - 1]) || (i + 1 < tokens.size && mask[i + 1]))
                ) {
                    mask[i] = true
                    changed = true
                }
            }
        }

        val spans = words.indices.filter { mask[it] }.map {
            Span(
                (words[it].start - PAD_SEC).coerceAtLeast(0.0),
                (words[it].end + PAD_SEC).coerceAtMost(clipDurationSec),
            )
        }
        return merge(spans)
    }

    fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end) {
                out[out.lastIndex] = Span(last.start, maxOf(last.end, s.end))
            } else {
                out += s
            }
        }
        return out
    }

    /** Replaces each span in 16-bit mono PCM with a quiet 1 kHz tone (or silence). */
    fun apply(pcm: ShortArray, sampleRate: Int, spans: List<Span>, beep: Boolean): ShortArray {
        val out = pcm.copyOf()
        for (s in spans) {
            val from = (s.start * sampleRate).toInt().coerceIn(0, out.size)
            val to = (s.end * sampleRate).toInt().coerceIn(0, out.size)
            for (i in from until to) {
                out[i] = if (beep) {
                    (Math.sin(2 * Math.PI * 1000.0 * (i - from) / sampleRate) * 5000).toInt().toShort()
                } else 0
            }
        }
        return out
    }
}
