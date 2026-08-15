package ru.workinprogress.petich.idempotency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RequestFingerprintTest {
    @Test
    fun `same parts always produce the same fingerprint`() {
        val a = RequestFingerprint.of("user1", "user2", 1000L)
        val b = RequestFingerprint.of("user1", "user2", 1000L)

        assertEquals(a, b)
    }

    @Test
    fun `different amounts produce different fingerprints`() {
        val a = RequestFingerprint.of("user1", "user2", 1000L)
        val b = RequestFingerprint.of("user1", "user2", 2000L)

        assertNotEquals(a, b)
    }

    @Test
    fun `different recipients produce different fingerprints`() {
        val a = RequestFingerprint.of("user1", "user2", 1000L)
        val b = RequestFingerprint.of("user1", "user3", 1000L)

        assertNotEquals(a, b)
    }
}
