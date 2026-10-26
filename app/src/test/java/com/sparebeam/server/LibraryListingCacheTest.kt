package com.sparebeam.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LibraryListingCacheTest {
    @Test fun repeatedRequestsShareOneScanAndExpire() {
        var calls = 0
        var clock = 10L
        val cache = LibraryListingCache({ "scan-${++calls}" }, { 0L }, { clock }, 50)
        assertEquals("scan-1", cache.get())
        clock = 59
        assertEquals("scan-1", cache.get())
        clock = 60
        assertEquals("scan-2", cache.get())
        assertEquals(2, calls)
    }

    @Test fun explicitRefreshBypassesCachedListing() {
        var calls = 0
        val cache = LibraryListingCache({ "scan-${++calls}" }, { 0L })
        assertEquals("scan-1", cache.get())
        assertEquals("scan-2", cache.get(forceRefresh = true))
        assertEquals("scan-2", cache.get())
    }

    @Test fun phoneSideChangesInvalidateImmediately() {
        var calls = 0
        var version = 0L
        val cache = LibraryListingCache({ "scan-${++calls}" }, { version })
        cache.get()
        version++
        assertEquals("scan-2", cache.get())
    }

    @Test fun changesDuringScanAreNotCached() {
        var calls = 0
        var version = 0L
        val cache = LibraryListingCache({ calls++; if (calls == 1) version++; "scan-$calls" }, { version })
        assertEquals("scan-1", cache.get())
        assertEquals("scan-2", cache.get())
        assertEquals("scan-2", cache.get())
    }

    @Test fun failedScanCanBeRetried() {
        var calls = 0
        val cache = LibraryListingCache({ check(++calls != 1); "ready" }, { 0L })
        assertTrue(runCatching { cache.get() }.isFailure)
        assertEquals("ready", cache.get())
        assertEquals(2, calls)
    }

    @Test fun concurrentBrowsersDoNotDuplicateTheScan() {
        val scans = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val cache = LibraryListingCache({ scans.incrementAndGet(); "listing" }, { 0L })
            val results = (1..8).map { pool.submit<String> { start.await(); cache.get() } }
            start.countDown()
            results.forEach { assertEquals("listing", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, scans.get())
        } finally { pool.shutdownNow() }
    }
}
