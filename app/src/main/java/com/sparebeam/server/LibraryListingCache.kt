package com.sparebeam.server

/** Short-lived, server-local cache. Authorization must be checked before calling get(). */
internal class LibraryListingCache(
    private val load: () -> String,
    private val revision: () -> Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMillis: Long = 5_000
) {
    private data class Snapshot(val revision: Long, val expiresAt: Long, val json: String)
    private var snapshot: Snapshot? = null

    @Synchronized
    fun get(forceRefresh: Boolean = false): String {
        val version = revision()
        val existing = snapshot
        if (!forceRefresh && existing != null && existing.revision == version && now() < existing.expiresAt) return existing.json
        val json = load()
        // A change during traversal must not leave an already-stale listing cached.
        snapshot = if (revision() == version) Snapshot(version, now() + lifetimeMillis, json) else null
        return json
    }
}
