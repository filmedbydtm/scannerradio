package com.filmedbydtm.scannerfilter.logic

import com.filmedbydtm.scannerfilter.audio.Mp3Header
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private val classifier = Classifier(Rules.parse(Rules.DEFAULT_TEXT))

    private fun words(s: String, conf: Double = 0.9) =
        s.split(" ").mapIndexed { i, w -> Word(w, i * 0.5, i * 0.5 + 0.4, conf) }

    private fun classify(s: String, dur: Double = 5.0, conf: Double = 0.9) =
        classifier.classify(words(s, conf), dur)

    @Test fun shortCallsAreSkipped() {
        assertEquals(Action.SKIP, classify("shots fired", dur = 1.5).action)
    }

    @Test fun priorityCallsPlay() {
        assertEquals("Shots fired", classify("units respond shots fired at the location").category)
        assertEquals(Action.PLAY, classify("shot spotter alert eight rounds").action)
        assertEquals(Action.PLAY, classify("pi accident on the freeway").action)
        assertEquals(Action.PLAY, classify("loose dogs in the alley").action)
        assertEquals(Action.PLAY, classify("caller at the marathon gas station").action)
        assertEquals(Action.PLAY, classify("prisoner transport to the d d c").action)
    }

    @Test fun gswBeatsMedicalButGasStationDoesNot() {
        val gsw = classify("medic en route for a gsw")
        assertEquals(Action.PLAY, gsw.action)
        assertEquals("GSW", gsw.category)
        assertEquals(Action.SKIP, classify("medic needed at the gas station for a sick person").action)
    }

    @Test fun neverCallsAreBlocked() {
        assertEquals(Action.SKIP, classify("shift change in ten minutes").action)
        assertEquals(Action.SKIP, classify("i can't log in to the mdt").action)
        assertEquals(Action.SKIP, classify("nine one one hang up").action)
        assertEquals(Action.SKIP, classify("shots fired requesting a supervisor").action)
        assertEquals(Action.SKIP, classify("unconscious person breathing").action)
        assertEquals(Action.SKIP, classify("suicidal subject").action)
        assertEquals(Action.SKIP, classify("missing child last seen").action)
    }

    @Test fun phoneNumbersAreBlocked() {
        val d = classify("caller is three one three five five five one two one two")
        assertEquals(Action.SKIP, d.action)
        assertEquals("Phone numbers", d.category)
        assertFalse(Text.looksLikePhoneNumber("one nine four five oh main".split(" ")))
    }

    @Test fun redactCalls() {
        assertEquals(Action.REDACT, classify("b and e in progress at one nine four five oh main").action)
        assertEquals(Action.REDACT, classify("noise complaint").action)
        assertEquals(Action.SKIP, classify("noise complaint", conf = 0.3).action)
        assertEquals(Action.IGNORE, classify("copy that heading in").action)
    }

    @Test fun redactorBleepsNumbersAndBirthDates() {
        val w = words("assault at one two three main date of birth march fifth")
        val spans = Redactor.findSpans(w, 10.0)
        val covered = { idx: Int -> spans.any { it.start <= w[idx].start && it.end >= w[idx].end } }
        assertTrue(covered(2)); assertTrue(covered(3)); assertTrue(covered(4))
        assertFalse(covered(0)); assertFalse(covered(5))
        assertTrue(covered(9)); assertTrue(covered(10))
    }

    @Test fun queueOrderAndExpiry() {
        val q = CallQueue()
        fun call(at: Long, prio: Boolean) = QueuedCall("f", at, ShortArray(0), 8000, prio, "c$at", "")
        val now = 1_000_000L
        q.add(call(now - 10_000, false))
        q.add(call(now - 5_000, true))
        assertEquals(now - 10_000, q.next(now)!!.startedAt) // not backed up: plain order
        q.clear()
        q.add(call(now - 6 * 60_000, false)) // stale low-priority
        q.add(call(now - 6 * 60_000 + 1, true)) // stale priority, dropped only in catch-up
        q.add(call(now - 90_000, false))
        q.add(call(now - 30_000, true))
        val first = q.next(now)!!
        assertTrue(first.priority)
        assertEquals(now - 30_000, first.startedAt)
        assertEquals(now - 90_000, q.next(now)!!.startedAt)
        assertNull(q.next(now))
    }

    @Test fun segmenterSplitsTransmissions() {
        val out = ArrayList<Segment>()
        val seg = Segmenter("f", threshold = 400) { out += it }
        val rate = 8000
        val loud = ShortArray(rate * 3) { if (it % 2 == 0) 3000 else -3000 }
        val quiet = ShortArray(rate * 2)
        seg.push(quiet, rate, 0); seg.push(loud, rate, 0); seg.push(quiet, rate, 0)
        seg.push(ShortArray(rate) { if (it % 2 == 0) 3000 else -3000 }, rate, 0); seg.push(quiet, rate, 0)
        assertEquals(2, out.size)
        assertTrue(out[0].durationSec in 3.0..3.7)
        assertTrue(out[1].durationSec in 1.0..1.7)
    }

    @Test fun mp3HeaderParses() {
        // MPEG-2 Layer III, 16 kbps, 22050 Hz, mono.
        val h = Mp3Header.parse(byteArrayOf(0xFF.toByte(), 0xF3.toByte(), 0x20, 0xC4.toByte()), 0)!!
        assertEquals(22050, h.sampleRate)
        assertEquals(1, h.channels)
        assertEquals(52, h.frameLength)
    }
}
