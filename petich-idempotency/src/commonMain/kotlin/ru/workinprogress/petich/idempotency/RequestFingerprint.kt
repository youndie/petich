package ru.workinprogress.petich.idempotency

// A hash of the request parameters that MATTER — quantity, target, resource id and the like. Not
// the idempotency key itself, and not fields such as a one-time confirmation code, which
// legitimately change between retries of ONE and the same operation. Compared in IdempotencyGuard.
//
// FNV-1a over UTF-8: portable, deterministic across calls and across platforms. String.hashCode()
// would be the obvious alternative and is the wrong one — its value is not guaranteed to be stable
// between Kotlin targets, so a fingerprint written on one platform would not match the same
// request read on another.
object RequestFingerprint {
    private const val FNV_OFFSET_BASIS = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193

    fun of(vararg parts: Any?): String {
        val key = parts.joinToString(" ") { it.toString() }
        var hash = FNV_OFFSET_BASIS
        for (byte in key.encodeToByteArray()) {
            hash = hash xor byte.toInt()
            hash *= FNV_PRIME
        }
        return (hash and 0x7FFFFFFF).toString(16)
    }
}
