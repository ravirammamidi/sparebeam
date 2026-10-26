package com.sparebeam.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object SharedFolders {
    private const val PREFS = "shared_folders"
    private const val KEY = "uris"
    fun list(context: Context): List<Uri> = context.getSharedPreferences(PREFS, 0).getStringSet(KEY, emptySet()).orEmpty().sorted().mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
    fun add(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet().apply { add(uri.toString()) }).apply()
        LibraryRevision.changed()
    }
    fun remove(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val prefs = context.getSharedPreferences(PREFS, 0)
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet().apply { remove(uri.toString()) }).apply()
        LibraryRevision.changed()
    }
    fun displayName(context: Context, uri: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifBlank { "Shared folder" } }.getOrDefault("Shared folder")
}
