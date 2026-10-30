package com.filmedbydtm.scannerfilter.audio

import com.filmedbydtm.scannerfilter.service.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpStreamTest {

    /** Serves one canned response per connection, recording each request's headers. */
    private fun server(vararg responses: (port: Int) -> String): Pair<Int, MutableList<String>> {
        val ss = ServerSocket(0)
        val requests = mutableListOf<String>()
        thread(isDaemon = true) {
            for (r in responses) {
                ss.accept().use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val req = StringBuilder()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        req.append(line).append('\n')
                    }
                    synchronized(requests) { requests += req.toString() }
                    s.getOutputStream().apply { write(r(ss.localPort).toByteArray(Charsets.ISO_8859_1)); flush() }
                }
            }
            ss.close()
        }
        return ss.localPort to requests
    }

    @Test fun followsRedirectAndAcceptsIcyStatus() {
        val (port, requests) = server(
            { p -> "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$p/relay\r\n\r\n" },
            { _ -> "ICY 200 OK\r\ncontent-type: audio/mpeg\r\n\r\nAUDIO" },
        )
        val http = HttpStream()
        val body = http.open("http://127.0.0.1:$port/13671").readBytes().toString(Charsets.ISO_8859_1)
        assertEquals("AUDIO", body)
        assertEquals("audio/mpeg", http.contentType)
        assertTrue(requests[0].contains("Referer: https://www.broadcastify.com/"))
        assertTrue(requests[0].contains("User-Agent: Mozilla/5.0"))
        assertTrue(requests[1].startsWith("GET /relay HTTP/1.1"))
    }

    @Test fun sendsBasicAuthFromLink() {
        val (port, requests) = server({ _ -> "HTTP/1.1 200 OK\r\n\r\nok" })
        HttpStream().open("http://me:p%40ss@127.0.0.1:$port/1.mp3").readBytes()
        assertTrue(requests[0].contains("Authorization: Basic bWU6cEBzcw=="))
    }

    @Test fun decodesChunkedBody() {
        val (port, _) = server(
            { _ -> "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n" },
        )
        assertEquals("abcde", HttpStream().open("http://127.0.0.1:$port/").readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test(expected = HttpStatusException::class)
    fun reportsHttpErrors() {
        val (port, _) = server({ _ -> "HTTP/1.1 403 Forbidden\r\n\r\n" })
        HttpStream().open("http://127.0.0.1:$port/")
    }

    @Test fun parsesGoogleDnsAnswer() {
        val json = """{"Status":0,"Answer":[{"name":"broadcastify.cdnstream1.com.","type":5,"data":"edge.example.net."},""" +
            """{"name":"edge.example.net.","type":1,"TTL":60,"data":"203.0.113.7"},{"type":1,"data": "198.51.100.2"}]}"""
        assertEquals(listOf("203.0.113.7", "198.51.100.2"), HttpStream.parseDohIpv4(json))
    }

    @Test fun feedLinesAcceptNumbersPagesAndUrls() {
        val feeds = Settings.parseFeeds(
            """
            # comment
            Detroit Police | 13671
            Fire | https://www.broadcastify.com/listen/feed/18629
            Other | http://example.com:8000/stream
            Bad | not a feed
            """.trimIndent(),
        )
        assertEquals(3, feeds.size)
        assertEquals(
            listOf("https://broadcastify.cdnstream1.com/13671", "https://audio.broadcastify.com/13671.mp3"),
            feeds[0].urls,
        )
        assertEquals("https://broadcastify.cdnstream1.com/18629", feeds[1].url)
        assertEquals(listOf("http://example.com:8000/stream"), feeds[2].urls)
    }
}
