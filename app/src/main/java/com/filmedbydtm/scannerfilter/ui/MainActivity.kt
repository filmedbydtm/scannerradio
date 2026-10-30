package com.filmedbydtm.scannerfilter.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.filmedbydtm.scannerfilter.audio.Transcriber
import com.filmedbydtm.scannerfilter.logic.Rules
import com.filmedbydtm.scannerfilter.service.ScannerService
import com.filmedbydtm.scannerfilter.service.ScannerState
import com.filmedbydtm.scannerfilter.service.Settings

/** Single screen: start/stop, live status, activity log and all settings. */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var modelButton: Button
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var modelMessage = ""

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Detroit Scanner Filter"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { startService(Intent(this@MainActivity, ScannerService::class.java).setAction(ScannerService.ACTION_STOP)) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons)

        modelButton = Button(this).apply { setOnClickListener { downloadModel() } }
        root.addView(modelButton)

        section(root, "Feeds")
        val feeds = multiline(settings.feedsText, 5)
        root.addView(feeds)

        section(root, "Playback")
        val minDur = number(root, "Skip transmissions shorter than (seconds)", settings.minDurationSec)
        val gap = number(root, "Pause between calls (seconds)", settings.gapSec)
        val lowAge = number(root, "Delete edited (low-priority) calls older than (minutes)", settings.lowPriorityMaxAgeMin)
        val catchUp = Switch(this).apply {
            text = "Catch-up mode: when backed up, drop calls over 5 min old and play priority calls first"
            isChecked = settings.catchUp
        }
        root.addView(catchUp)
        val backlog = number(root, "Count as backed up when the oldest waiting call is (seconds)", settings.backlogSec)
        val beep = Switch(this).apply {
            text = "Bleep edited numbers (off = silence)"
            isChecked = settings.beep
        }
        root.addView(beep)
        val gate = number(root, "Squelch level (raise if static triggers calls, lower if calls are missed)", settings.gateThreshold.toDouble())

        section(root, "Call rules")
        root.addView(TextView(this).apply {
            text = "BUCKET | Category | keywords. NEVER always wins; PLAY+ beats MEDICAL; REDACT calls get numbers and birth dates bleeped."
            textSize = 12f
        })
        val rules = multiline(settings.rulesText, 14).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        root.addView(rules)
        root.addView(Button(this).apply {
            text = "Reset rules to defaults"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Replace your rules with the defaults?")
                    .setPositiveButton("Reset") { _, _ -> rules.setText(Rules.DEFAULT_TEXT) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                settings.feedsText = feeds.text.toString()
                settings.rulesText = rules.text.toString()
                minDur.text.toString().toDoubleOrNull()?.let { settings.minDurationSec = it }
                gap.text.toString().toDoubleOrNull()?.let { settings.gapSec = it }
                lowAge.text.toString().toDoubleOrNull()?.let { settings.lowPriorityMaxAgeMin = it }
                backlog.text.toString().toDoubleOrNull()?.let { settings.backlogSec = it }
                gate.text.toString().toDoubleOrNull()?.let { settings.gateThreshold = it.toInt() }
                settings.catchUp = catchUp.isChecked
                settings.beep = beep.isChecked
                val n = Rules.parse(settings.rulesText).size
                toast("Saved ($n rule categories). Stop and start to apply.")
            }
        })

        section(root, "Activity")
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun start() {
        if (settings.feeds().isEmpty()) { toast("Add at least one feed URL and tap Save first."); return }
        if (!Transcriber.isModelReady(filesDir)) { toast("Download the speech model first."); return }
        startForegroundService(Intent(this, ScannerService::class.java))
    }

    private fun downloadModel() {
        modelButton.isEnabled = false
        Thread {
            try {
                Transcriber.downloadModel(filesDir) { modelMessage = it }
            } catch (e: Exception) {
                modelMessage = "Download failed: ${e.message}"
            }
            runOnUiThread { modelButton.isEnabled = true }
        }.start()
    }

    private fun refresh() {
        val s = ScannerState
        status.text = buildString {
            append(if (s.running) "● " else "○ ").append(s.status)
            if (s.nowPlaying.isNotEmpty()) append("\nNow playing: ").append(s.nowPlaying)
            append("\nWaiting: ${s.queued}   Played: ${s.played}   Blocked: ${s.skipped}   Ignored: ${s.ignored}")
            if (modelMessage.isNotEmpty()) append("\n").append(modelMessage)
        }
        modelButton.text = if (Transcriber.isModelReady(filesDir)) "Speech model installed (tap to re-download)"
        else "Download speech model (~40 MB, one time)"
        logView.text = s.recent().joinToString("\n")
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun multiline(value: String, lines: Int) = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines
        isSingleLine = false
        setHorizontallyScrolling(false)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun number(root: LinearLayout, label: String, value: Double): EditText {
        root.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        val e = EditText(this).apply {
            setText(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(e)
        return e
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
