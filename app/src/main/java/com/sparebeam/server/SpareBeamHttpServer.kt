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

class SpareBeamHttpServer(private val context: Context) : NanoHTTPD("0.0.0.0", 8080) {
    override fun serve(session: IHTTPSession): Response {
        val path = runCatching { URLDecoder.decode(session.uri, "UTF-8") }.getOrDefault("/")
        if (path == "/") return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", INDEX)
        if (path == "/api/list") return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", listing())
        if (path.startsWith("/media/")) {
            val parts = path.removePrefix("/media/").split('/', limit = 2)
            val index = parts.firstOrNull()?.toIntOrNull() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            val root = SharedFolders.list(context).getOrNull(index) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            val target = parts.getOrNull(1).orEmpty()
            val uri = resolve(root, target) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val length = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L }.getOrDefault(-1L)
            val range = session.headers["range"]?.let { RangeRequest.parse(it, length) }
            if (range is RangeRequest.Invalid) return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range")
            val start = (range as? RangeRequest.Valid)?.start ?: 0L
            val count = (range as? RangeRequest.Valid)?.length ?: length
            val input = context.contentResolver.openInputStream(uri) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            input.skip(start)
            val status = if (range is RangeRequest.Valid) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = newFixedLengthResponse(status, mime, LimitedInputStream(input, count), count)
            response.addHeader("Accept-Ranges", "bytes")
            if (range is RangeRequest.Valid && length >= 0) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
            return response
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
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
    companion object { private val INDEX = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>SpareBeam</title><style>body{font:16px system-ui;background:#111715;color:#f4f7f5;max-width:900px;margin:40px auto;padding:0 20px}a{color:#8cf5cc}.item{padding:14px;border-bottom:1px solid #34413b}video,audio,img{max-width:100%;margin-top:10px}</style><h1>SpareBeam</h1><p>Your media on your network.</p><main id="files">Loading…</main><script>fetch('/api/list').then(r=>r.json()).then(data=>{files.innerHTML=data.map(f=>`<div class="item"><a href="${'$'}{f.url}">${'$'}{f.name}</a>${'$'}{f.type.startsWith('video/')?`<video controls src="${'$'}{f.url}"></video>`:f.type.startsWith('audio/')?`<audio controls src="${'$'}{f.url}"></audio>`:f.type.startsWith('image/')?`<img src="${'$'}{f.url}">`:''}</div>`).join('')}).catch(()=>files.textContent='Could not load media.')</script>""" }
}
