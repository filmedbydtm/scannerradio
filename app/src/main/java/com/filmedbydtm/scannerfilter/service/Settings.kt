package com.filmedbydtm.scannerfilter.service

import android.content.Context
import com.filmedbydtm.scannerfilter.logic.Rules

data class Feed(val name: String, val url: String)

/** All user settings, stored in SharedPreferences. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** One feed per line: "Name | https://stream-url". */
    var feedsText: String
        get() = p.getString("feeds", DEFAULT_FEEDS)!!
        set(v) = p.edit().putString("feeds", v).apply()

    var rulesText: String
        get() = p.getString("rules", Rules.DEFAULT_TEXT)!!
        set(v) = p.edit().putString("rules", v).apply()

    var minDurationSec: Double
        get() = p.getFloat("minDuration", 2f).toDouble()
        set(v) = p.edit().putFloat("minDuration", v.toFloat()).apply()

    var gapSec: Double
        get() = p.getFloat("gap", 3f).toDouble()
        set(v) = p.edit().putFloat("gap", v.toFloat()).apply()

    var lowPriorityMaxAgeMin: Double
        get() = p.getFloat("lowMaxAge", 5f).toDouble()
        set(v) = p.edit().putFloat("lowMaxAge", v.toFloat()).apply()

    var catchUp: Boolean
        get() = p.getBoolean("catchUp", true)
        set(v) = p.edit().putBoolean("catchUp", v).apply()

    var backlogSec: Double
        get() = p.getFloat("backlog", 60f).toDouble()
        set(v) = p.edit().putFloat("backlog", v.toFloat()).apply()

    var gateThreshold: Int
        get() = p.getInt("gate", 400)
        set(v) = p.edit().putInt("gate", v).apply()

    /** Test mode: play every transmission, skipping all filtering. */
    var playAll: Boolean
        get() = p.getBoolean("playAll", false)
        set(v) = p.edit().putBoolean("playAll", v).apply()

    var beep: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit().putBoolean("beep", v).apply()

    fun feeds(): List<Feed> = parseFeeds(feedsText)

    companion object {
        const val DEFAULT_FEEDS = """# One feed per line:  Name | Broadcastify feed number, feed page link, or stream URL
Detroit Police | 13671
Detroit Fire | 18629
"""

        private val FEED_PAGE = Regex("""broadcastify\.com/(?:listen/)?(?:feed|webPlayer)/(\d+)""", RegexOption.IGNORE_CASE)

        /** Accepts a feed number, a broadcastify.com feed page link, or a direct stream URL. */
        fun streamUrl(value: String): String? {
            val v = value.trim()
            if (v.all { it.isDigit() } && v.isNotEmpty()) return "https://broadcastify.cdnstream1.com/$v"
            FEED_PAGE.find(v)?.let { return "https://broadcastify.cdnstream1.com/${it.groupValues[1]}" }
            if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
            return null
        }

        fun parseFeeds(text: String): List<Feed> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf('|')
                val name = if (i < 0) line else line.substring(0, i).trim()
                val url = streamUrl(if (i < 0) line else line.substring(i + 1)) ?: return@mapNotNull null
                Feed(name, url)
            }
            .toList()
    }
}
