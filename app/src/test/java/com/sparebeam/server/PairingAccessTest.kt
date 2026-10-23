package com.sparebeam.server

import org.junit.Assert.*
import org.junit.Test

class PairingAccessTest {
    @Test fun issuesSessionForCorrectCodeAndChecksCookie() {
        val access = PairingAccess("123456")
        val token = access.pair("123456")
        assertNotNull(token)
        assertTrue(access.isAuthorized("other=value; sparebeam_session=$token"))
    }

    @Test fun rejectsWrongCodeAndUnknownSession() {
        val access = PairingAccess("123456")
        assertNull(access.pair("654321"))
        assertFalse(access.isAuthorized("sparebeam_session=unknown"))
    }

    @Test fun expiresSessions() {
        var now = 100L
        val access = PairingAccess("123456", now = { now })
        val token = access.pair("123456")!!
        assertTrue(access.isAuthorized("sparebeam_session=$token"))
        now += 12 * 60 * 60 * 1000L
        assertFalse(access.isAuthorized("sparebeam_session=$token"))
    }

    @Test fun locksPairingAfterRepeatedFailures() {
        var now = 100L
        val access = PairingAccess("123456", now = { now })
        repeat(8) { assertNull(access.pair("wrong")) }
        assertNull(access.pair("123456"))
        now += 60_001L
        assertNotNull(access.pair("123456"))
    }
}
