package com.sparebeam.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.sparebeam.storage.ImportedMediaStore
import com.sparebeam.storage.SharedFolders
import com.sparebeam.storage.LibraryRevision
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Listens only on the host phone's Wi-Fi interface, never on every network interface. */
class SpareBeamHttpServer(private val context: Context, wifiAddress: String) : NanoHTTPD(wifiAddress, 8080) {
    private val access = PairingAccess(context)
    private val listingCache = LibraryListingCache(::listing, LibraryRevision::current)
    private val authority = "$wifiAddress:8080"
    private val origin = "http://$authority"
    private val homePage by lazy { context.assets.open("index.html").bufferedReader().use { it.readText() } }
    private val contentSecurityPolicy by lazy {
        val script = homePage.substringAfter("<script>", "").substringBefore("</script>")
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray(Charsets.UTF_8)))
        "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; " +
            "script-src 'sha256-$hash'; style-src 'unsafe-inline'; img-src 'self'; " +
            "media-src 'self'; connect-src 'self'; object-src 'none'"
    }
    override fun serve(session: IHTTPSession): Response {
        val response = route(session)
        if (session.method == Method.HEAD) {
            // NanoHTTPD 2.3.1 otherwise sends a body even for HEAD.
            response.data?.close()
            response.data = ByteArrayInputStream(ByteArray(0))
        }
        return response
    }

    override fun useGzipWhenAccepted(response: Response): Boolean =
        response.requestMethod != Method.HEAD && super.useGzipWhenAccepted(response)

    private fun route(session: IHTTPSession): Response {
        // NanoHTTPD already percent-decodes the path. Decoding again corrupts literal +/% names.
        val path = session.uri
        if (session.headers["host"]?.lowercase() != authority) {
            return error(Response.Status.BAD_REQUEST, "Use the address shown on the phone.")
        }
        if (session.headers["origin"]?.let { it != origin } == true) {
            return error(Response.Status.FORBIDDEN, "Cross-origin requests are not allowed.")
        }
        if (path == "/api/auth") {
            if (session.method != Method.POST) return methodNotAllowed("POST")
            return authenticate(session)
        }
        if (session.method != Method.GET && session.method != Method.HEAD) return methodNotAllowed("GET, HEAD")
        if (path == "/") {
            // The server supplies only a session bit, never private media data in the HTML.
            // The script hash is unchanged; every data request still checks authorization.
            val paired = access.isAuthorized(session.headers["cookie"].orEmpty())
            val page = homePage.replace("<body class=\"checking\">", "<body class=\"checking\" data-paired=\"$paired\">")
            return secure(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page))
        }
        if (!access.isAuthorized(session.headers["cookie"].orEmpty())) return secure(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "Pair this browser with the code shown in SpareBeam."))
        if (path == "/api/list") {
            val fresh = session.parameters["refresh"]?.firstOrNull() == "1"
            return secure(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", listingCache.get(fresh)))
        }
        if (path.startsWith("/local-media/")) {
            val file = ImportedMediaStore.fileForRequest(context, path.removePrefix("/local-media/"))
                ?: return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            return streamFile(file, session)
        }
        if (path.startsWith("/media/")) {
            val parts = path.removePrefix("/media/").split('/', limit = 2)
            val index = parts.firstOrNull()?.toIntOrNull() ?: return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            val root = SharedFolders.list(context).getOrNull(index) ?: return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            val target = parts.getOrNull(1).orEmpty()
            val uri = resolve(root, target) ?: return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val length = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }.getOrDefault(-1L)
            val range = session.headers["range"]?.let { RangeRequest.parse(it, length) }
            if (range is RangeRequest.Invalid) return secure(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range").also { if (length >= 0) it.addHeader("Content-Range", "bytes */$length") })
            val start = (range as? RangeRequest.Valid)?.start ?: 0L
            val count = (range as? RangeRequest.Valid)?.length ?: length
            val input = context.contentResolver.openInputStream(uri) ?: return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
            if (!skipFully(input, start)) { input.close(); return secure(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to seek media")) }
            val status = if (range is RangeRequest.Valid) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = when {
                count >= 0 -> newFixedLengthResponse(status, mime, LimitedInputStream(input, count), count)
                session.method == Method.HEAD -> object : Response(status, mime, input, 0) {
                    // Providers may not know the size. Omit Content-Length rather than inventing it.
                    override fun sendContentLengthHeaderIfNotAlreadyPresent(pw: PrintWriter, defaultSize: Long): Long = 0
                }
                else -> newChunkedResponse(status, mime, input)
            }
            response.addHeader("Accept-Ranges", "bytes")
            if (range is RangeRequest.Valid && length >= 0) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
            return secure(mediaDisposition(response, mime, target.substringAfterLast('/')))
        }
        return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
    }
    private fun authenticate(session: IHTTPSession): Response {
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Pairing requires JSON.")
        }
        if (session.headers.containsKey("transfer-encoding")) return error(Response.Status.BAD_REQUEST, "Unsupported request framing.")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return error(Response.Status.LENGTH_REQUIRED, "Content-Length is required.")
        if (length < 1 || length > 1_024) return error(Response.Status.PAYLOAD_TOO_LARGE, "Pairing request is too large.")
        val body = HashMap<String, String>()
        return try {
            session.parseBody(body)
            val pin = JSONObject(body["postData"].orEmpty()).optString("pin").take(16)
            val token = access.pair(pin)
            if (token == null) secure(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"error\":\"Invalid code or temporarily locked\"}"))
            else secure(newFixedLengthResponse(Response.Status.OK, "application/json", "{\"paired\":true}").also {
                it.addHeader("Set-Cookie", "sparebeam_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200")
            })
        } catch (_: Exception) {
            secure(newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Invalid request\"}"))
        }
    }
    private fun error(status: Response.Status, message: String): Response =
        secure(newFixedLengthResponse(status, MIME_PLAINTEXT, message))

    private fun methodNotAllowed(allow: String): Response =
        error(Response.Status.METHOD_NOT_ALLOWED, "This server provides read-only media access.").also { it.addHeader("Allow", allow) }

    private fun mediaDisposition(response: Response, mime: String, name: String): Response = response.apply {
        val safeImage = mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/heic", "image/heif")
        if (!safeImage && !mime.startsWith("audio/") && !mime.startsWith("video/")) {
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            addHeader("Content-Disposition", "attachment; filename=\"download\"; filename*=UTF-8''$encoded")
        }
    }

    private fun secure(response: Response): Response = response.apply {
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Cache-Control", "no-store")
        addHeader("Content-Security-Policy", contentSecurityPolicy)
        addHeader("Cross-Origin-Resource-Policy", "same-origin")
        addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
    }
    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else return false
        }
        return true
    }
    private fun listing(): String {
        val result = JSONArray()
        SharedFolders.list(context).forEachIndexed { index, root ->
            runCatching {
                val parent = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                walk(root, parent, "", index, result, 0)
            }
        }
        ImportedMediaStore.files(context).forEach { (file, relative) ->
            if (result.length() >= 2000) return@forEach
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            val url = "/local-media/${relative.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"
            result.put(JSONObject().put("name", file.name).put("type", mime).put("url", url))
        }
        return result.toString()
    }
    private fun streamFile(file: File, session: IHTTPSession): Response {
        val length = file.length()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val range = session.headers["range"]?.let { RangeRequest.parse(it, length) }
        if (range is RangeRequest.Invalid) return secure(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range").also { it.addHeader("Content-Range", "bytes */$length") })
        val start = (range as? RangeRequest.Valid)?.start ?: 0L
        val count = (range as? RangeRequest.Valid)?.length ?: length
        val input = runCatching { FileInputStream(file) }.getOrElse { return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")) }
        if (!skipFully(input, start)) { input.close(); return secure(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to seek media")) }
        val status = if (range is RangeRequest.Valid) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = newFixedLengthResponse(status, mime, LimitedInputStream(input, count), count)
        response.addHeader("Accept-Ranges", "bytes")
        if (range is RangeRequest.Valid) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
        return secure(mediaDisposition(response, mime, file.name))
    }
    private fun walk(root: Uri, parent: Uri, relative: String, index: Int, out: JSONArray, depth: Int) {
        if (depth > 12 || out.length() >= 2000) return
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent))
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext() && out.length() < 2000) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    val childPath = listOf(relative, name).filter { it.isNotBlank() }.joinToString("/")
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val child = DocumentsContract.buildDocumentUriUsingTree(root, c.getString(0))
                        walk(root, child, childPath, index, out, depth + 1)
                    }
                    else out.put(JSONObject().put("name", name).put("type", mime).put("url", "/media/$index/${childPath.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"))
                }
            }
        }
    }
    private fun resolve(root: Uri, relative: String): Uri? {
        if (relative.contains("..") || relative.startsWith('/')) return null
        return runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(root)
            var current = DocumentsContract.buildDocumentUriUsingTree(root, rootId)
            for (part in relative.split('/').filter { it.isNotBlank() }) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(current))
                val cursor = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null) ?: return null
                current = cursor.use { c ->
                    var found: Uri? = null
                    while (c.moveToNext()) if (c.getString(1) == part) { found = DocumentsContract.buildDocumentUriUsingTree(root, c.getString(0)); break }
                    found
                } ?: return null
            }
            current
        }.getOrNull()
    }
    private class LimitedInputStream(input: InputStream, private var remaining: Long) : java.io.FilterInputStream(input) {
        override fun read(): Int { if (remaining <= 0) return -1; val b = super.read(); if (b >= 0) remaining--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if (remaining <= 0) return -1; val n = super.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n > 0) remaining -= n; return n }
    }
}
