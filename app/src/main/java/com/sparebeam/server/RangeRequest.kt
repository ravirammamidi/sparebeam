package com.sparebeam.server

sealed interface RangeRequest {
    data class Valid(val start: Long, val end: Long) : RangeRequest { val length get() = end - start + 1 }
    data object Invalid : RangeRequest
    companion object {
        fun parse(header: String, size: Long): RangeRequest {
            if (size < 0 || !header.startsWith("bytes=")) return Invalid
            val pair = header.removePrefix("bytes=").substringBefore(',').split('-', limit = 2)
            if (pair.size != 2) return Invalid
            val first = pair[0].toLongOrNull()
            val last = pair[1].toLongOrNull()
            val start = first ?: if (last != null && last > 0) (size - last).coerceAtLeast(0) else return Invalid
            val end = (last ?: size - 1).coerceAtMost(size - 1)
            return if (start < 0 || start >= size || end < start) Invalid else Valid(start, end)
        }
    }
}
