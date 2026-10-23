package com.sparebeam.server

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Local pairing gate. Pairing codes persist on this device; browser sessions expire after 12 hours. */
class PairingAccess internal constructor(val code: String, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(pairingCode(context), System::currentTimeMillis)
    private val sessions = ConcurrentHashMap<String, Long>()
    private var failedAttempts = 0
    private var lockedUntil = 0L

    @Synchronized
    fun pair(candidate: String): String? {
        val timestamp = now()
        if (timestamp < lockedUntil) return null
        val matches = MessageDigest.isEqual(code.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
        if (!matches) {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                failedAttempts = 0
                lockedUntil = timestamp + LOCKOUT_MS
            }
            return null
        }
        failedAttempts = 0
        sessions.entries.removeIf { it.value <= timestamp }
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        val tokenBytes = ByteArray(32).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        sessions[token] = timestamp + SESSION_MS
        return token
    }

    fun isAuthorized(cookieHeader: String): Boolean {
        val token = cookieHeader.split(';').asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("sparebeam_session=") }
            ?.substringAfter('=') ?: return false
        val expires = sessions[token] ?: return false
        if (now() >= expires) { sessions.remove(token); return false }
        return true
    }

    companion object {
        private const val PREFS = "sparebeam_pairing"
        private const val CODE_KEY = "pairing_code"
        private const val MAX_ATTEMPTS = 8
        private const val MAX_SESSIONS = 32
        private const val LOCKOUT_MS = 60_000L
        private const val SESSION_MS = 12 * 60 * 60 * 1000L
        private val secureRandom = SecureRandom()

        fun pairingCode(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(CODE_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000))
            return synchronized(this) {
                prefs.getString(CODE_KEY, null) ?: generated.also { prefs.edit().putString(CODE_KEY, it).commit() }
            }
        }
    }
}
