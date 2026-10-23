package com.sparebeam.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.sparebeam.storage.SharedFolders
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Listens only on the host phone's Wi-Fi interface, never on every network interface. */
class SpareBeamHttpServer(private val context: Context, wifiAddress: String) : NanoHTTPD(wifiAddress, 8080) {
    private val access = PairingAccess(context)
    override fun serve(session: IHTTPSession): Response {
        val path = runCatching { URLDecoder.decode(session.uri, "UTF-8") }.getOrDefault("/")
        if (path == "/") return secure(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", context.assets.open("index.html").bufferedReader().use { it.readText() }))
        if (path == "/api/auth" && session.method == Method.POST) return authenticate(session)
        if (!access.isAuthorized(session.headers["cookie"].orEmpty())) return secure(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain; charset=utf-8", "Pair this browser with the code shown in SpareBeam."))
        if (path == "/api/list") return secure(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", listing()))
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
            val response = if (count >= 0) newFixedLengthResponse(status, mime, LimitedInputStream(input, count), count) else newChunkedResponse(status, mime, input)
            response.addHeader("Accept-Ranges", "bytes")
            if (range is RangeRequest.Valid && length >= 0) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
            return secure(response)
        }
        return secure(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"))
    }
    private fun authenticate(session: IHTTPSession): Response {
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
    private fun secure(response: Response): Response = response.apply {
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Cache-Control", "no-store")
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
        SharedFolders.list(context).forEachIndexed { index, root -> walk(root, "", index, result, 0) }
        return result.toString()
    }
    private fun walk(root: Uri, relative: String, index: Int, out: JSONArray, depth: Int) {
        if (depth > 12 || out.length() >= 2000) return
        runCatching {
            val baseId = DocumentsContract.getTreeDocumentId(root)
            val parent = if (relative.isBlank()) DocumentsContract.buildDocumentUriUsingTree(root, baseId) else resolve(root, relative) ?: return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent))
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext() && out.length() < 2000) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    val childPath = listOf(relative, name).filter { it.isNotBlank() }.joinToString("/")
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(root, childPath, index, out, depth + 1)
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
