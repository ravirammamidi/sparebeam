package com.sparebeam.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** Copies only the items selected by the user into app-private storage. */
object ImportedMediaStore {
    data class Folder(val name: String, val fileCount: Int)
    data class CopyResult(val copied: Int, val failed: Int)

    fun root(context: Context): File = File(context.filesDir, ROOT_NAME).apply { mkdirs() }

    fun folders(context: Context): List<Folder> = root(context).listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name != ".." }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
        .map { Folder(it.name, countFiles(it)) }

    fun createFolder(context: Context, rawName: String): String? {
        val name = rawName.trim()
        if (name.isBlank() || name.length > 80 || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) return null
        val folder = File(root(context), name)
        if (folder.isDirectory) return name
        return if (folder.mkdir()) { LibraryRevision.changed(); name } else null
    }

    fun copyInto(context: Context, folderName: String, sources: List<Uri>): CopyResult {
        val root = root(context).canonicalFile
        val folder = File(root, folderName).canonicalFile
        require(folder.parentFile == root && folder.isDirectory) { "Folder is not in SpareBeam storage" }
        var copied = 0
        var failed = 0
        sources.forEach { uri ->
            runCatching {
                val name = displayName(context, uri)
                val destination = nextAvailableFile(folder, safeFileName(name))
                val temporary = File.createTempFile("sparebeam-import-", ".tmp", folder)
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot read selected media" }
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    check(temporary.renameTo(destination)) { "Could not save copied media" }
                } finally {
                    temporary.delete()
                }
                copied++
            }.onFailure { failed++ }
        }
        if (copied > 0) LibraryRevision.changed()
        return CopyResult(copied, failed)
    }

    fun fileForRequest(context: Context, relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return runCatching {
            val base = root(context).canonicalFile
            val file = File(base, relativePath).canonicalFile
            if (file.path.startsWith(base.path + File.separator) && file.isFile) file else null
        }.getOrNull()
    }

    fun files(context: Context): List<Pair<File, String>> {
        val base = root(context).canonicalFile
        val result = mutableListOf<Pair<File, String>>()
        fun visit(directory: File, depth: Int) {
            if (depth > 12 || result.size >= 2000) return
            directory.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                if (child.isDirectory) visit(child, depth + 1)
                else if (child.isFile && !child.name.startsWith("sparebeam-import-")) result += child to child.relativeTo(base).invariantSeparatorsPath
                if (result.size >= 2000) return
            }
        }
        visit(base, 0)
        return result
    }

    private fun countFiles(folder: File): Int = folder.walkTopDown().count { it.isFile }
    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "media"
    }

    private fun safeFileName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\').trim().replace(Regex("[\\u0000-\\u001f]"), "_")
        return leaf.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "media"
    }

    private fun nextAvailableFile(folder: File, name: String): File {
        val original = File(folder, name)
        if (!original.exists()) return original
        val extension = name.lastIndexOf('.').takeIf { it > 0 }?.let { name.substring(it) }.orEmpty()
        val stem = name.removeSuffix(extension)
        var index = 2
        while (true) {
            val candidate = File(folder, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private const val ROOT_NAME = "SpareBeam"
}
