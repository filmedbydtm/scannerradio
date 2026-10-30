package com.filmedbydtm.scannerfilter.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Reads an endless MP3 (Icecast / Broadcastify) stream over HTTP, splits it into MP3 frames and
 * decodes them with the platform decoder. Calls [onPcm] with mono 16-bit samples.
 */
class Mp3StreamDecoder(
    private val url: String,
    /** Called once the server has accepted the request. */
    private val onConnected: (contentType: String?) -> Unit = {},
    /** Called with the number of raw bytes read from the network. */
    private val onBytes: (Int) -> Unit = {},
    private val onPcm: (pcm: ShortArray, sampleRate: Int) -> Unit,
) {
    @Volatile private var running = true
    private val http = HttpStream()

    fun stop() {
        running = false
        http.close()
    }

    /** Blocks until [stop] or the connection fails (throws). */
    fun run() {
        var raw = http.open(url)
        val type = http.contentType?.lowercase() ?: ""
        if ("html" in type) {
            http.close()
            throw java.io.IOException("Got a web page, not audio. Use the feed number or stream URL.")
        }
        if ("mpegurl" in type || "pls" in type || "text/" in type) {
            // A playlist instead of audio: follow the first stream link in it.
            val bytes = ByteArray(65_536)
            var n = 0
            while (n < bytes.size) {
                val r = raw.read(bytes, n, bytes.size - n)
                if (r < 0) break
                n += r
            }
            val body = String(bytes, 0, n, Charsets.UTF_8)
            http.close()
            val link = Regex("https?://[^\\s\"'<>]+").find(body)?.value
                ?: throw java.io.IOException("Feed URL returned ${http.contentType}, not audio")
            raw = http.open(link)
        }
        onConnected(http.contentType)
        val counted = object : FilterInputStream(raw) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) onBytes(it) }
        }
        try {
            decode(counted)
        } finally {
            http.close()
        }
        if (running) throw java.io.IOException("Stream ended")
    }

    private fun decode(input: InputStream) {
        val reader = FrameReader(input)
        var codec: MediaCodec? = null
        var codecRate = 0
        var codecChannels = 0
        var outRate = 0
        var outChannels = 1
        var ptsUs = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val frame = reader.nextFrame() ?: break
                if (codec == null || frame.sampleRate != codecRate || frame.channels != codecChannels) {
                    codec?.let { it.stop(); it.release() }
                    codecRate = frame.sampleRate
                    codecChannels = frame.channels
                    outRate = codecRate
                    outChannels = codecChannels
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG).apply {
                        configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, codecRate, codecChannels), null, null, 0)
                        start()
                    }
                }
                val dec = codec!!
                var queued = false
                while (!queued && running) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(frame.data)
                        dec.queueInputBuffer(inIdx, 0, frame.data.size, ptsUs, 0)
                        ptsUs += frame.samples * 1_000_000L / frame.sampleRate
                        queued = true
                    }
                    while (true) {
                        val outIdx = dec.dequeueOutputBuffer(info, 0)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = dec.outputFormat
                            outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            continue
                        }
                        if (outIdx < 0) break
                        if (info.size > 0) {
                            val ob = dec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val all = ShortArray(sb.remaining())
                            sb.get(all)
                            onPcm(downmix(all, outChannels), outRate)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
        } finally {
            codec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        }
    }

    private fun downmix(s: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return s
        val out = ShortArray(s.size / channels)
        for (i in out.indices) {
            var sum = 0
            for (ch in 0 until channels) sum += s[i * channels + ch]
            out[i] = (sum / channels).toShort()
        }
        return out
    }
}
