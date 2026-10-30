package com.filmedbydtm.scannerfilter.logic

import kotlin.math.sqrt

class Segment(val feed: String, val startedAt: Long, val pcm: ShortArray, val sampleRate: Int) {
    val durationSec: Double get() = pcm.size.toDouble() / sampleRate
}

/**
 * Splits a continuous scanner feed into individual transmissions using a simple loudness gate:
 * a transmission starts when a 20 ms frame is louder than [threshold] and ends after
 * [hangMs] of quiet. Very long transmissions are cut at [maxSec].
 */
class Segmenter(
    private val feed: String,
    var threshold: Int = 400,
    private val hangMs: Int = 900,
    private val preRollMs: Int = 200,
    private val maxSec: Int = 60,
    private val onSegment: (Segment) -> Unit,
) {
    private var sampleRate = 0
    private var frameLen = 0
    private var frame = ShortArray(0)
    private var frameFill = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private var active: ShortArrayBuilder? = null
    private var activeStartedAt = 0L
    private var quietMs = 0

    /** Loudest 20 ms frame since the last [takePeak] call; useful for setting the squelch. */
    @Volatile private var peak = 0
    @Volatile var segmentCount = 0
        private set

    fun takePeak(): Int = peak.also { peak = 0 }

    /** Feed mono 16-bit PCM. [now] is the wall-clock time of the end of this chunk. */
    fun push(pcm: ShortArray, rate: Int, now: Long) {
        if (rate != sampleRate) {
            flush(now)
            sampleRate = rate
            frameLen = rate / 50
            frame = ShortArray(frameLen)
            frameFill = 0
            preRoll.clear()
        }
        for (s in pcm) {
            frame[frameFill++] = s
            if (frameFill == frameLen) {
                processFrame(frame.copyOf(), now)
                frameFill = 0
            }
        }
    }

    private fun processFrame(f: ShortArray, now: Long) {
        var sum = 0.0
        for (s in f) sum += s.toDouble() * s
        val rms = sqrt(sum / f.size)
        if (rms > peak) peak = rms.toInt()
        val loud = rms > threshold
        val cur = active
        if (cur == null) {
            preRoll.addLast(f)
            while (preRoll.size * 20 > preRollMs) preRoll.removeFirst()
            if (loud) {
                val b = ShortArrayBuilder()
                preRoll.forEach { b.add(it) }
                activeStartedAt = now - preRoll.size * 20L
                preRoll.clear()
                active = b
                quietMs = 0
            }
        } else {
            cur.add(f)
            quietMs = if (loud) 0 else quietMs + 20
            if (quietMs >= hangMs || cur.size >= maxSec * sampleRate) emit()
        }
    }

    fun flush(@Suppress("UNUSED_PARAMETER") now: Long) {
        if (active != null) emit()
    }

    private fun emit() {
        val b = active ?: return
        active = null
        // Trim most of the trailing silence but keep a little tail.
        val keepTail = (sampleRate * 0.25).toInt()
        val trim = ((quietMs / 20) * frameLen - keepTail).coerceAtLeast(0)
        val pcm = b.toArray(b.size - trim)
        quietMs = 0
        segmentCount++
        if (pcm.isNotEmpty()) onSegment(Segment(feed, activeStartedAt, pcm, sampleRate))
    }
}

class ShortArrayBuilder {
    private var buf = ShortArray(16_000)
    var size = 0
        private set

    fun add(a: ShortArray) {
        if (size + a.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + a.size))
        System.arraycopy(a, 0, buf, size, a.size)
        size += a.size
    }

    fun toArray(n: Int = size): ShortArray = buf.copyOf(n.coerceIn(0, size))
}
