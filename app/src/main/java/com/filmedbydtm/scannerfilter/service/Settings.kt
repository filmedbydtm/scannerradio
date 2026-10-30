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

    var beep: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit().putBoolean("beep", v).apply()

    fun feeds(): List<Feed> = feedsText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val i = line.indexOf('|')
            if (i < 0) Feed(line, line) else Feed(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        .filter { it.url.startsWith("http") }
        .toList()

    companion object {
        const val DEFAULT_FEEDS = """# One feed per line:  Name | stream URL
# Broadcastify feed pages look like broadcastify.com/listen/feed/12345
# The stream URL for that feed is https://broadcastify.cdnstream1.com/12345
# Detroit Police | https://broadcastify.cdnstream1.com/XXXXX
# Detroit Fire | https://broadcastify.cdnstream1.com/XXXXX
"""
    }
}
