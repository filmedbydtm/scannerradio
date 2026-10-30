package com.filmedbydtm.scannerfilter.audio

import java.io.InputStream

class Mp3Frame(val data: ByteArray, val sampleRate: Int, val channels: Int, val samples: Int)

/** Finds MPEG audio Layer III frames in a raw byte stream, resyncing on garbage. */
class FrameReader(private val input: InputStream) {
    private var buf = ByteArray(65_536)
    private var len = 0
    private var synced = false

    private fun fill(min: Int): Boolean {
        while (len < min) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = input.read(buf, len, buf.size - len)
            if (n < 0) return false
            len += n
        }
        return true
    }

    private fun consume(n: Int) {
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    fun nextFrame(): Mp3Frame? {
        while (true) {
            if (!fill(4)) return null
            val h = Mp3Header.parse(buf, 0)
            if (h == null) { synced = false; consume(1); continue }
            // When not yet locked on, require the following header to be valid too.
            val need = if (synced) h.frameLength else h.frameLength + 4
            if (!fill(need)) return null
            if (!synced) {
                val next = Mp3Header.parse(buf, h.frameLength)
                if (next == null || next.sampleRate != h.sampleRate) { consume(1); continue }
                synced = true
            }
            val data = buf.copyOfRange(0, h.frameLength)
            consume(h.frameLength)
            return Mp3Frame(data, h.sampleRate, h.channels, h.samplesPerFrame)
        }
    }
}

class Mp3Header(val sampleRate: Int, val channels: Int, val frameLength: Int, val samplesPerFrame: Int) {
    companion object {
        private val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1)
        private val BITRATE_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1)
        private val RATE_V1 = intArrayOf(44100, 48000, 32000)
        private val RATE_V2 = intArrayOf(22050, 24000, 16000)
        private val RATE_V25 = intArrayOf(11025, 12000, 8000)

        fun parse(b: ByteArray, off: Int): Mp3Header? {
            if (off + 4 > b.size) return null
            val h = ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
            if ((h ushr 21) and 0x7FF != 0x7FF) return null
            val version = (h ushr 19) and 3          // 0 = 2.5, 2 = 2, 3 = 1
            val layer = (h ushr 17) and 3            // 1 = Layer III
            val brIdx = (h ushr 12) and 0xF
            val srIdx = (h ushr 10) and 3
            val padding = (h ushr 9) and 1
            val mode = (h ushr 6) and 3
            if (version == 1 || layer != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3) return null
            val v1 = version == 3
            val bitrate = (if (v1) BITRATE_V1_L3 else BITRATE_V2_L3)[brIdx] * 1000
            val rate = when (version) { 3 -> RATE_V1; 2 -> RATE_V2; else -> RATE_V25 }[srIdx]
            val len = (if (v1) 144 else 72) * bitrate / rate + padding
            if (len < 24) return null
            return Mp3Header(rate, if (mode == 3) 1 else 2, len, if (v1) 1152 else 576)
        }
    }
}
