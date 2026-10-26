package com.sparebeam.storage

import java.util.concurrent.atomic.AtomicLong

/** Invalidates in-memory listings immediately after phone-side library changes. */
internal object LibraryRevision {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    fun changed() { value.incrementAndGet() }
}
