package com.filmedbydtm.scannerfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.filmedbydtm.scannerfilter.audio.ClipPlayer
import com.filmedbydtm.scannerfilter.audio.Mp3StreamDecoder
import com.filmedbydtm.scannerfilter.audio.Transcriber
import com.filmedbydtm.scannerfilter.logic.Action
import com.filmedbydtm.scannerfilter.logic.CallQueue
import com.filmedbydtm.scannerfilter.logic.Classifier
import com.filmedbydtm.scannerfilter.logic.QueueSettings
import com.filmedbydtm.scannerfilter.logic.QueuedCall
import com.filmedbydtm.scannerfilter.logic.Redactor
import com.filmedbydtm.scannerfilter.logic.Rules
import com.filmedbydtm.scannerfilter.logic.Segment
import com.filmedbydtm.scannerfilter.logic.Segmenter
import com.filmedbydtm.scannerfilter.ui.MainActivity
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs the whole pipeline:
 *   feed stream -> split into transmissions -> speech-to-text -> filter / edit -> play queue.
 */
class ScannerService : Service() {

    @Volatile private var running = false
    private val threads = ArrayList<Thread>()
    private val decoders = ArrayList<Mp3StreamDecoder>()
    private val toTranscribe = LinkedBlockingQueue<Segment>()
    private val playQueue = CallQueue()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startInForeground()
        startPipeline()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ScannerState.running = false
        ScannerState.status = "Stopped"
        decoders.forEach { it.stop() }
        ClipPlayer.stop()
        threads.forEach { it.interrupt() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startPipeline() {
        val settings = Settings(this)
        val feeds = settings.feeds()
        if (feeds.isEmpty()) {
            ScannerState.status = "No feeds set up. Add a feed URL first."
            stopSelf(); return
        }
        if (!Transcriber.isModelReady(filesDir)) {
            ScannerState.status = "Speech model not downloaded yet."
            stopSelf(); return
        }
        running = true
        ScannerState.running = true
        ScannerState.resetCounters()
        ScannerState.status = "Starting…"
        playQueue.clear()
        playQueue.settings = QueueSettings(
            lowPriorityMaxAgeMs = (settings.lowPriorityMaxAgeMin * 60_000).toLong(),
            backlogThresholdMs = (settings.backlogSec * 1000).toLong(),
            catchUpEnabled = settings.catchUp,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScannerFilter::pipeline")
            .apply { acquire() }

        for (feed in feeds) {
            threads += thread("capture-${feed.name}") { captureLoop(feed, settings.gateThreshold) }
        }
        threads += thread("transcribe") { transcribeLoop(settings) }
        threads += thread("play") { playLoop(settings) }
        ScannerState.status = (if (settings.playAll) "TEST MODE (playing everything) · " else "") +
            "Listening to ${feeds.joinToString { it.name }}"
    }

    private fun thread(name: String, body: () -> Unit) = Thread {
        try { body() } catch (_: InterruptedException) {}
    }.apply { this.name = name; isDaemon = true; start() }

    private fun captureLoop(feed: Feed, gate: Int) {
        var backoff = 2_000L
        var totalBytes = 0L
        val status = { text: String -> ScannerState.feedStatus[feed.name] = text }
        while (running) {
            val segmenter = Segmenter(feed.name, threshold = gate) { toTranscribe.put(it) }
            var lastReport = 0L
            var bytesSinceReport = 0L
            val decoder = Mp3StreamDecoder(
                feed.url,
                onConnected = { type ->
                    status("connected (${type ?: "unknown type"}), waiting for audio…")
                    ScannerState.log(System.currentTimeMillis(), "[${feed.name}] connected: $type")
                },
                onBytes = { n ->
                    totalBytes += n
                    bytesSinceReport += n
                },
                onPcm = { pcm, rate ->
                    val now = System.currentTimeMillis()
                    segmenter.push(pcm, rate, now)
                    if (now - lastReport >= 1000) {
                        val kbps = if (lastReport == 0L) 0 else bytesSinceReport * 8 / (now - lastReport)
                        lastReport = now
                        bytesSinceReport = 0
                        status(
                            "receiving ${kbps} kbps · ${totalBytes / 1024} KB · ${rate} Hz · " +
                                "level ${segmenter.takePeak()} (squelch $gate) · ${segmenter.segmentCount} transmissions",
                        )
                    }
                },
            )
            synchronized(decoders) { decoders += decoder }
            try {
                status("connecting to ${feed.url}…")
                decoder.run()
                backoff = 2_000L
            } catch (e: Exception) {
                if (!running) break
                val msg = when {
                    e is com.filmedbydtm.scannerfilter.audio.HttpStatusException && e.code == 403 ->
                        "refused by Broadcastify (HTTP 403). The feed may be offline or blocked."
                    e is com.filmedbydtm.scannerfilter.audio.HttpStatusException && e.code == 404 ->
                        "feed not found (HTTP 404). Check the feed number."
                    else -> "${e.javaClass.simpleName}: ${e.message}"
                }
                status("connection failed: $msg · retrying in ${backoff / 1000}s")
                ScannerState.log(System.currentTimeMillis(), "[${feed.name}] connection failed: $msg")
            } finally {
                synchronized(decoders) { decoders -= decoder }
                segmenter.flush(System.currentTimeMillis())
            }
            if (!running) break
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun transcribeLoop(settings: Settings) {
        val classifier = Classifier(Rules.parse(settings.rulesText), settings.minDurationSec)
        val beep = settings.beep
        val playAll = settings.playAll
        Transcriber(Transcriber.modelDir(filesDir)).use { asr ->
            while (running) {
                val seg = toTranscribe.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val now = System.currentTimeMillis()
                if (seg.durationSec < settings.minDurationSec) {
                    ScannerState.skipped++
                    continue
                }
                if (settings.catchUp && now - seg.startedAt > playQueue.settings.catchUpMaxAgeMs) {
                    ScannerState.skipped++
                    ScannerState.log(seg.startedAt, "[${seg.feed}] dropped before transcribing (over 5 min behind)")
                    continue
                }
                val words = asr.transcribe(seg.pcm, seg.sampleRate)
                val transcript = words.joinToString(" ") { it.text }
                val d = classifier.classify(words, seg.durationSec)
                val tag = "[${seg.feed}] ${d.category}"
                if (playAll) {
                    playQueue.add(QueuedCall(seg.feed, seg.startedAt, seg.pcm, seg.sampleRate, true, "TEST ${d.category}", transcript))
                    ScannerState.log(seg.startedAt, "TEST MODE would ${d.action} $tag: $transcript")
                    ScannerState.queued = playQueue.size()
                    continue
                }
                when (d.action) {
                    Action.PLAY -> {
                        playQueue.add(QueuedCall(seg.feed, seg.startedAt, seg.pcm, seg.sampleRate, true, d.category, transcript))
                        ScannerState.log(seg.startedAt, "QUEUED  $tag (${d.reason}): $transcript")
                    }
                    Action.REDACT -> {
                        val spans = Redactor.findSpans(words, seg.durationSec)
                        val edited = Redactor.apply(seg.pcm, seg.sampleRate, spans, beep)
                        playQueue.add(QueuedCall(seg.feed, seg.startedAt, edited, seg.sampleRate, false, d.category, transcript))
                        ScannerState.log(seg.startedAt, "QUEUED (edited, ${spans.size} cut) $tag: $transcript")
                    }
                    Action.SKIP -> {
                        ScannerState.skipped++
                        ScannerState.log(seg.startedAt, "BLOCKED $tag (${d.reason})")
                    }
                    Action.IGNORE -> {
                        ScannerState.ignored++
                        ScannerState.log(seg.startedAt, "ignored [${seg.feed}]: $transcript")
                    }
                }
                ScannerState.queued = playQueue.size()
            }
        }
    }

    private fun playLoop(settings: Settings) {
        val gapMs = (settings.gapSec * 1000).toLong()
        while (running) {
            val call = playQueue.next(System.currentTimeMillis()) { c, why ->
                ScannerState.skipped++
                ScannerState.log(c.startedAt, "DROPPED [${c.feed}] ${c.label} ($why)")
            }
            ScannerState.queued = playQueue.size()
            if (call == null) {
                Thread.sleep(250)
                continue
            }
            val ageSec = (System.currentTimeMillis() - call.startedAt) / 1000
            ScannerState.nowPlaying = "[${call.feed}] ${call.label} (${ageSec}s ago)"
            ClipPlayer.play(call.pcm, call.sampleRate)
            ScannerState.played++
            ScannerState.nowPlaying = ""
            Thread.sleep(gapMs)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scanner", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScannerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Scanner Filter is running")
            .setContentText("Playing filtered Detroit police & fire calls")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "scanner"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "stop"
    }
}
