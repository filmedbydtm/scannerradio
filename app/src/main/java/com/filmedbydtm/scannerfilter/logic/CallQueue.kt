package com.filmedbydtm.scannerfilter.logic

class QueuedCall(
    val feed: String,
    /** Wall-clock time the transmission started, in ms. */
    val startedAt: Long,
    val pcm: ShortArray,
    val sampleRate: Int,
    val priority: Boolean,
    val label: String,
    val transcript: String,
)

data class QueueSettings(
    /** Low-priority (edited) calls are deleted once they are older than this. */
    val lowPriorityMaxAgeMs: Long = 5 * 60_000L,
    /** When the oldest waiting call is this old, the queue counts as "backed up". */
    val backlogThresholdMs: Long = 60_000L,
    /** While backed up, drop every call older than this and play priority calls first. */
    val catchUpEnabled: Boolean = true,
    val catchUpMaxAgeMs: Long = 5 * 60_000L,
)

/**
 * Holds calls waiting to be played.
 *
 * Normally calls play strictly in the order they were transmitted. When the queue is backed up
 * (and catch-up is on), anything older than five minutes is dropped and priority calls jump
 * ahead of the edited, low-priority ones.
 */
class CallQueue(var settings: QueueSettings = QueueSettings()) {

    private val items = ArrayList<QueuedCall>()

    @Synchronized
    fun add(call: QueuedCall) {
        items += call
        items.sortBy { it.startedAt }
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun isBackedUp(now: Long): Boolean {
        val oldest = items.minOfOrNull { it.startedAt } ?: return false
        return now - oldest >= settings.backlogThresholdMs
    }

    /**
     * Removes and returns the next call to play, or null. [dropped] receives every call that was
     * discarded for being too old.
     */
    @Synchronized
    fun next(now: Long, dropped: (QueuedCall, String) -> Unit = { _, _ -> }): QueuedCall? {
        val it = items.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (!c.priority && now - c.startedAt > settings.lowPriorityMaxAgeMs) {
                it.remove(); dropped(c, "edited call older than 5 min")
            }
        }
        val backedUp = isBackedUp(now)
        if (backedUp && settings.catchUpEnabled) {
            val it2 = items.iterator()
            while (it2.hasNext()) {
                val c = it2.next()
                if (now - c.startedAt > settings.catchUpMaxAgeMs) {
                    it2.remove(); dropped(c, "catching up: older than 5 min")
                }
            }
        }
        if (items.isEmpty()) return null
        val pick = if (backedUp && settings.catchUpEnabled) {
            items.firstOrNull { it.priority } ?: items.first()
        } else {
            items.first()
        }
        items.remove(pick)
        return pick
    }
}
