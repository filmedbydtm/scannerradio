package com.filmedbydtm.scannerfilter.audio

import com.filmedbydtm.scannerfilter.logic.Word
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Offline speech-to-text with Vosk. Runs entirely on the phone. */
class Transcriber(modelDir: File) : AutoCloseable {

    private val model = Model(modelDir.absolutePath)

    fun transcribe(pcm: ShortArray, sampleRate: Int): List<Word> {
        val audio = resample(pcm, sampleRate, TARGET_RATE)
        val words = ArrayList<Word>()
        Recognizer(model, TARGET_RATE.toFloat()).use { rec ->
            rec.setWords(true)
            val chunk = 4000
            var i = 0
            while (i < audio.size) {
                val n = minOf(chunk, audio.size - i)
                val part = audio.copyOfRange(i, i + n)
                if (rec.acceptWaveForm(part, n)) words += parse(rec.result)
                i += n
            }
            words += parse(rec.finalResult)
        }
        return words
    }

    private fun parse(json: String): List<Word> {
        val arr = JSONObject(json).optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Word(o.getString("word"), o.getDouble("start"), o.getDouble("end"), o.optDouble("conf", 1.0))
        }
    }

    override fun close() = model.close()

    companion object {
        const val TARGET_RATE = 16_000
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

        fun modelDir(filesDir: File) = File(filesDir, "model/$MODEL_NAME")

        fun isModelReady(filesDir: File) = File(modelDir(filesDir), "am").isDirectory

        /** Downloads and unzips the ~40 MB English model. Blocking; call off the main thread. */
        fun downloadModel(filesDir: File, progress: (String) -> Unit) {
            val root = File(filesDir, "model").apply { deleteRecursively(); mkdirs() }
            val zip = File(filesDir, "model.zip")
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val b = ByteArray(65_536)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; progress("Downloading speech model… $pct%") }
                    }
                }
            }
            progress("Unpacking speech model…")
            val rootPath = root.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(rootPath)) continue
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            zip.delete()
            if (!isModelReady(filesDir)) throw IllegalStateException("Model unpacked but not found")
            progress("Speech model ready")
        }

        fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || input.isEmpty()) return input
            val outLen = (input.size.toLong() * to / from).toInt()
            val out = ShortArray(outLen)
            val step = from.toDouble() / to
            for (i in 0 until outLen) {
                val pos = i * step
                val j = pos.toInt()
                val frac = pos - j
                val a = input[minOf(j, input.size - 1)]
                val b = input[minOf(j + 1, input.size - 1)]
                out[i] = (a + (b - a) * frac).toInt().toShort()
            }
            return out
        }
    }
}
