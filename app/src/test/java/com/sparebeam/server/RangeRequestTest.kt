package com.sparebeam.server

import org.junit.Assert.*
import org.junit.Test

class RangeRequestTest {
    @Test fun parsesClosedRange() { assertEquals(RangeRequest.Valid(3, 7), RangeRequest.parse("bytes=3-7", 20)) }
    @Test fun parsesOpenEndedRange() { assertEquals(RangeRequest.Valid(8, 19), RangeRequest.parse("bytes=8-", 20)) }
    @Test fun parsesSuffixRange() { assertEquals(RangeRequest.Valid(15, 19), RangeRequest.parse("bytes=-5", 20)) }
    @Test fun rejectsOutOfBoundsRange() { assertEquals(RangeRequest.Invalid, RangeRequest.parse("bytes=20-", 20)) }
    @Test fun rejectsMalformedRange() { assertEquals(RangeRequest.Invalid, RangeRequest.parse("bytes=broken", 20)) }
}
