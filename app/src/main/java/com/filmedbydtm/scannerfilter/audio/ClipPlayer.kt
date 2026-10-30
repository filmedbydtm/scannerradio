package com.filmedbydtm.scannerfilter.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Plays one mono 16-bit clip and blocks until it has finished. */
object ClipPlayer {

    @Volatile private var current: AudioTrack? = null

    fun play(pcm: ShortArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 2))
            .build()
        current = track
        try {
            track.play()
            var off = 0
            while (off < pcm.size && current === track) {
                val n = track.write(pcm, off, minOf(4096, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            // Wait for the hardware to finish playing what was written.
            val deadline = System.currentTimeMillis() + 2_000 + pcm.size * 1000L / sampleRate
            while (current === track && track.playbackHeadPosition < off &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(50)
        } finally {
            current = null
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    fun stop() {
        current = null
    }
}
