package dev.modstreaming.backend.token

import dev.modstreaming.api.auth.ArtifactCoordinates
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A short-lived, limited-use permission to download one artifact. */
class DownloadGrant(
    val token: String,
    val coordinates: ArtifactCoordinates,
    val artifactId: String,
    val subject: String?,
    val expiresAt: Instant,
    maxUses: Int,
) {
    private val remaining = AtomicInteger(maxUses)

    fun tryConsume(now: Instant): Boolean {
        if (now.isAfter(expiresAt)) return false
        while (true) {
            val current = remaining.get()
            if (current <= 0) return false
            if (remaining.compareAndSet(current, current - 1)) return true
        }
    }
}

/**
 * Issues opaque download tokens after a successful authorization. Tokens are 256 random bits from
 * [SecureRandom], expire after [ttl] and may be used [maxUses] times (retries after a broken
 * connection). Kept in memory; a clustered deployment would back this with a shared cache.
 */
class DownloadTokenService(
    private val ttl: Duration = Duration.ofMinutes(5),
    private val maxUses: Int = 3,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, DownloadGrant>()

    fun issue(coordinates: ArtifactCoordinates, artifactId: String, subject: String?): DownloadGrant {
        purgeExpired()
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val grant = DownloadGrant(token, coordinates, artifactId, subject, clock.instant().plus(ttl), maxUses)
        grants[token] = grant
        return grant
    }

    /** Returns the grant if [token] is valid and consumes one use, else `null`. */
    fun redeem(token: String): DownloadGrant? {
        val grant = grants[token] ?: return null
        if (!grant.tryConsume(clock.instant())) {
            grants.remove(token)
            return null
        }
        return grant
    }

    fun purgeExpired() {
        val now = clock.instant()
        grants.entries.removeIf { now.isAfter(it.value.expiresAt) }
    }

    val size: Int get() = grants.size
}
