package com.sparebeam

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sparebeam.server.PairingAccess
import com.sparebeam.server.SpareBeamHttpServer
import com.sparebeam.storage.ImportedMediaStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerSecurityTest {
    private lateinit var context: Context
    private lateinit var server: SpareBeamHttpServer
    private lateinit var fixture: File
    private val authority = "127.0.0.1:8080"

    @Before fun startServer() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "security-${System.nanoTime()}"
        ImportedMediaStore.createFolder(context, name)
        fixture = File(ImportedMediaStore.root(context), name)
        File(fixture, "original.txt").writeText("original media bytes")
        File(fixture, "page.html").writeText("<script>alert('untrusted file')</script>")
        File(fixture, "image.svg").writeText("<svg xmlns='http://www.w3.org/2000/svg'><script>alert('untrusted file')</script></svg>")
        server = SpareBeamHttpServer(context, "127.0.0.1")
        server.start(5_000, false)
    }

    @After fun stopServer() {
        if (::server.isInitialized) server.stop()
        if (::fixture.isInitialized) fixture.deleteRecursively()
    }

    @Test fun pairingIsRequiredAndReadRequestsStillWork() {
        val path = "/local-media/${fixture.name}/original.txt"
        assertEquals(401, request("GET", "/api/list").status)
        assertEquals(401, request("GET", path).status)
        assertTrue(request("GET", "/").body.toString(Charsets.UTF_8).contains("data-paired=\"false\""))
        val cookie = pair()
        assertTrue(request("GET", "/", cookie).body.toString(Charsets.UTF_8).contains("data-paired=\"true\""))
        assertEquals(200, request("GET", "/api/list", cookie).status)
        assertEquals(401, request("GET", "/api/list").status)
        assertEquals("original media bytes", request("GET", path, cookie).body.toString(Charsets.UTF_8))
        val head = request("HEAD", path, cookie)
        assertEquals(200, head.status)
        assertTrue(head.body.isEmpty())
        assertEquals("20", head.headers["content-length"])
        val compressedHead = request("HEAD", "/", extra = mapOf("Accept-Encoding" to "gzip"))
        assertEquals(200, compressedHead.status)
        assertTrue(compressedHead.body.isEmpty())
        assertFalse(compressedHead.headers.containsKey("content-encoding"))
        val partial = request("GET", path, cookie, mapOf("Range" to "bytes=0-7"))
        assertEquals(206, partial.status)
        assertEquals("original", partial.body.toString(Charsets.UTF_8))
    }

    @Test fun listingCacheRefreshesExternalChangesAndInvalidatesImportsImmediately() {
        val cookie = pair()
        val first = request("GET", "/api/list", cookie).body.toString(Charsets.UTF_8)
        val external = "external-${System.nanoTime()}.txt"
        File(fixture, external).writeText("external change")
        assertEquals(first, request("GET", "/api/list", cookie).body.toString(Charsets.UTF_8))
        assertTrue(request("GET", "/api/list?refresh=1", cookie).body.toString(Charsets.UTF_8).contains(external))
        val source = File(context.cacheDir, "managed-${System.nanoTime()}.txt")
        try {
            source.writeText("managed import")
            ImportedMediaStore.copyInto(context, fixture.name, listOf(android.net.Uri.fromFile(source)))
            assertTrue(request("GET", "/api/list", cookie).body.toString(Charsets.UTF_8).contains(source.name))
        } finally { source.delete() }
    }

    @Test fun writeMethodsCannotChangeFilesOrCreateUploads() {
        val cookie = pair()
        val original = File(fixture, "original.txt")
        val before = original.readBytes()
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            for (path in listOf("/", "/api/list", "/local-media/${fixture.name}/original.txt", "/media/0/original.txt", "/api/upload")) {
                val response = request(method, path, cookie, body = "replacement bytes")
                assertEquals("$method $path", 405, response.status)
                assertEquals("GET, HEAD", response.headers["allow"])
            }
        }
        assertEquals(405, request("PUT", "/local-media/${fixture.name}/uploaded.txt", cookie, body = "upload").status)
        assertFalse(File(fixture, "uploaded.txt").exists())
        assertArrayEquals(before, original.readBytes())
    }

    @Test fun pairingRejectsForeignOriginsMultipartAndOversizedBodies() {
        val body = "{\"pin\":\"${PairingAccess.pairingCode(context)}\"}"
        assertEquals(403, request("POST", "/api/auth", extra = mapOf("Origin" to "http://untrusted.example", "Content-Type" to "application/json"), body = body).status)
        assertEquals(400, request("GET", "/", extra = mapOf("Host" to "untrusted.example:8080")).status)
        assertEquals(415, request("POST", "/api/auth", extra = mapOf("Content-Type" to "multipart/form-data; boundary=test"), body = body).status)
        assertEquals(413, request("POST", "/api/auth", extra = mapOf("Content-Type" to "application/json"), body = "x".repeat(1_025)).status)
        assertEquals(400, request("POST", "/api/auth", extra = mapOf("Content-Type" to "application/json", "Transfer-Encoding" to "chunked"), body = body).status)
        assertEquals(405, request("GET", "/api/auth").status)
        assertEquals(200, request("POST", "/api/auth", extra = mapOf("Origin" to "http://$authority", "Content-Type" to "application/json"), body = body).status)
    }

    @Test fun activeFilesAreAttachmentsAndOnlyTheAppScriptIsAllowed() {
        val cookie = pair()
        for (name in listOf("page.html", "image.svg")) {
            val response = request("GET", "/local-media/${fixture.name}/$name", cookie)
            assertEquals(200, response.status)
            assertTrue(response.headers["content-disposition"]!!.startsWith("attachment;"))
            assertEquals("nosniff", response.headers["x-content-type-options"])
        }
        val home = request("GET", "/")
        val html = home.body.toString(Charsets.UTF_8)
        val script = html.substringAfter("<script>").substringBefore("</script>")
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))
        val csp = home.headers["content-security-policy"]!!
        assertTrue(csp.contains("script-src 'sha256-$hash'"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        assertTrue(csp.contains("connect-src 'self'"))
        assertEquals("same-origin", home.headers["cross-origin-resource-policy"])
        assertFalse(home.headers.containsKey("access-control-allow-origin"))
    }

    @Test fun pathsStayInsideSharedStorageAndAreDecodedOnlyOnce() {
        val cookie = pair()
        val name = "%2e%2e+notes.txt"
        File(fixture, name).writeText("literal encoded filename")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        assertEquals("literal encoded filename", request("GET", "/local-media/${fixture.name}/$encoded", cookie).body.toString(Charsets.UTF_8))
        for (path in listOf("/local-media/%2e%2e/shared_prefs/sparebeam_pairing.xml", "/local-media/${fixture.name}/%2e%2e/%2e%2e/secret.txt", "/media/0/%2e%2e/secret.txt")) {
            assertEquals(path, 404, request("GET", path, cookie).status)
        }
    }

    private fun pair(): String {
        val response = request("POST", "/api/auth", extra = mapOf("Content-Type" to "application/json"), body = "{\"pin\":\"${PairingAccess.pairingCode(context)}\"}")
        assertEquals(200, response.status)
        return response.headers["set-cookie"]!!.substringBefore(';')
    }

    private data class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(method: String, path: String, cookie: String? = null, extra: Map<String, String> = emptyMap(), body: String = ""): Reply {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf("Host" to authority, "Connection" to "close")
        if (cookie != null) headers["Cookie"] = cookie
        if (bytes.isNotEmpty()) headers["Content-Length"] = bytes.size.toString()
        headers.putAll(extra)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 8080), 5_000)
            socket.soTimeout = 5_000
            val output = socket.getOutputStream()
            output.write(("$method $path HTTP/1.1\r\n" + headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n\r\n").toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
            val input = DataInputStream(socket.getInputStream().buffered())
            fun line(): String {
                val buffer = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    check(byte >= 0) { "Incomplete HTTP headers" }
                    if (byte == 10) return buffer.toString("ISO-8859-1").removeSuffix("\r")
                    buffer.write(byte)
                    check(buffer.size() < 65_536) { "HTTP header too large" }
                }
            }
            val status = line().split(' ')[1].toInt()
            val responseHeaders = linkedMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                responseHeaders[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            // Read the framed response, not until TCP close: early rejection may reset unread request data.
            val responseBody = if (method == "HEAD") input.readBytes() else {
                val length = responseHeaders["content-length"]?.toIntOrNull()
                if (length == null) input.readBytes() else ByteArray(length).also(input::readFully)
            }
            Reply(status, responseHeaders, responseBody)
        }
    }
}
