package com.filmedbydtm.scannerfilter.audio

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code from $url")

/**
 * Minimal HTTP GET for endless audio streams. Unlike HttpURLConnection it follows redirects
 * between https and http, accepts SHOUTcast's "ICY 200 OK" status line, and sends the
 * browser-style headers Broadcastify's stream servers expect.
 */
class HttpStream(private val connectTimeoutMs: Int = 8_000, private val readTimeoutMs: Int = 30_000) {

    @Volatile private var socket: Socket? = null
    var finalUrl: String = ""
        private set
    var contentType: String? = null
        private set

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
    }

    fun open(url: String): InputStream {
        var uri = URI(url)
        repeat(MAX_REDIRECTS + 1) {
            val https = uri.scheme.equals("https", ignoreCase = true)
            val host = uri.host ?: throw IOException("Bad URL: $uri")
            val port = if (uri.port > 0) uri.port else if (https) 443 else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            val plain = connect(host, port)
            val s: Socket = if (https) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    ssl.close()
                    throw IOException("Certificate does not match $host")
                }
                ssl
            } else plain
            socket = s

            val hostHeader = if (uri.port > 0) "$host:$port" else host
            val req = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                HEADERS.forEach { (k, v) -> append("$k: $v\r\n") }
                uri.rawUserInfo?.let {
                    val auth = java.net.URLDecoder.decode(it, "UTF-8").toByteArray(Charsets.UTF_8)
                    append("Authorization: Basic ${java.util.Base64.getEncoder().encodeToString(auth)}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            s.getOutputStream().apply { write(req.toByteArray(Charsets.ISO_8859_1)); flush() }

            val input = BufferedInputStream(s.getInputStream(), 16_384)
            val statusLine = readLine(input) ?: throw IOException("No response from $host")
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Bad response from $host: $statusLine")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }

            if (code in 300..399 && headers["location"] != null) {
                s.close()
                uri = uri.resolve(headers["location"]!!.replace(" ", "%20"))
                return@repeat
            }
            if (code !in 200..299) {
                s.close()
                throw HttpStatusException(code, uri.toString())
            }
            finalUrl = uri.toString()
            contentType = headers["content-type"]
            return if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                ChunkedInputStream(input)
            } else input
        }
        throw IOException("Too many redirects from $url")
    }

    /**
     * Tries every address the host resolves to, IPv4 first. If none of the phone's DNS answers
     * work (ad blockers and "Private DNS" often fake answers for cdnstream1.com), the host is
     * looked up again with Google's DNS-over-HTTPS and those addresses are tried.
     */
    private fun connect(host: String, port: Int): Socket {
        val tried = ArrayList<String>()
        var last: Exception? = null
        val system = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: IOException) {
            last = e
            emptyList()
        }
        tryAddresses(system, port, tried) { last = it }?.let { return it }

        val doh = try {
            resolveWithDoh(host)
        } catch (e: Exception) {
            tried += "Google DNS lookup failed: ${e.message}"
            emptyList()
        }
        tryAddresses(doh.filter { a -> system.none { it == a } }, port, tried) { last = it }?.let { return it }

        val blocked = system.isNotEmpty() && system.all { it.isAnyLocalAddress || it.isLoopbackAddress }
        throw IOException(
            buildString {
                append("Could not reach $host:$port. ")
                if (blocked) append("Your phone's DNS is blocking this site (ad blocker or Private DNS). ")
                append("Tried: ${tried.joinToString(", ").ifEmpty { "no addresses" }}. ")
                append("Last error: ${last?.message}")
            },
            last,
        )
    }

    private fun tryAddresses(
        addresses: List<InetAddress>,
        port: Int,
        tried: MutableList<String>,
        onError: (Exception) -> Unit,
    ): Socket? {
        for (addr in addresses.sortedBy { if (it is Inet4Address) 0 else 1 }) {
            if (addr.isAnyLocalAddress) {
                tried += "${addr.hostAddress} (fake/blocked)"
                continue
            }
            val sock = Socket()
            socket = sock
            try {
                sock.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                sock.soTimeout = readTimeoutMs
                return sock
            } catch (e: IOException) {
                try { sock.close() } catch (_: Exception) {}
                tried += "${addr.hostAddress} (${e.message?.substringAfterLast(": ") ?: e.javaClass.simpleName})"
                onError(e)
            }
        }
        return null
    }

    /** Looks up IPv4 addresses for [host] via Google's DNS-over-HTTPS JSON API. */
    private fun resolveWithDoh(host: String): List<InetAddress> {
        val c = java.net.URL("https://dns.google/resolve?name=$host&type=A").openConnection() as HttpsURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = connectTimeoutMs
        try {
            return parseDohIpv4(c.inputStream.bufferedReader().readText()).map { InetAddress.getByName(it) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 6

        val HEADERS = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://www.broadcastify.com/",
            "Origin" to "https://www.broadcastify.com",
            "Icy-MetaData" to "0",
        )

        private val DOH_IPV4 = Regex("\"data\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+\\.\\d+)\"")

        /** Pulls the IPv4 addresses out of a dns.google JSON answer. */
        fun parseDohIpv4(json: String): List<String> = DOH_IPV4.findAll(json).map { it.groupValues[1] }.toList()

        /** Reads one CRLF/LF-terminated header line (ISO-8859-1). */
        fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) throw IOException("Header line too long")
            }
        }
    }
}

/** Decodes HTTP chunked transfer encoding. */
class ChunkedInputStream(private val input: InputStream) : InputStream() {
    private var remaining = 0
    private var done = false

    private fun nextChunk(): Boolean {
        if (done) return false
        val line = HttpStream.readLine(input) ?: return false.also { done = true }
        val size = line.substringBefore(';').trim().ifEmpty {
            return nextChunk() // blank line after a chunk's data
        }.toInt(16)
        if (size == 0) { done = true; return false }
        remaining = size
        return true
    }

    override fun read(): Int {
        if (remaining == 0 && !nextChunk()) return -1
        val b = input.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0 && !nextChunk()) return -1
        val n = input.read(b, off, minOf(len, remaining))
        if (n > 0) remaining -= n
        return n
    }

    override fun close() = input.close()
}
