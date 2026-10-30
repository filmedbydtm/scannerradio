package com.filmedbydtm.scannerfilter.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared, in-memory status that the screen polls. */
object ScannerState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var nowPlaying = ""
    @Volatile var queued = 0
    @Volatile var played = 0
    @Volatile var skipped = 0
    @Volatile var ignored = 0

    /** Per-feed connection status line, keyed by feed name. */
    val feedStatus = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val log = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("h:mm:ss a", Locale.US)

    @Synchronized
    fun log(at: Long, line: String) {
        log.addFirst("${fmt.format(Date(at))}  $line")
        while (log.size > 200) log.removeLast()
    }

    @Synchronized
    fun recent(): List<String> = log.toList()

    fun resetCounters() {
        played = 0; skipped = 0; ignored = 0; queued = 0; nowPlaying = ""
        feedStatus.clear()
    }
}
