package dev.modstreaming.backend.auth

import dev.modstreaming.api.auth.ArtifactCoordinates

/**
 * What the backend knows about a verification request. [credentials] is the generic structure sent
 * by the client; its meaning is entirely up to the [AuthorizationProvider]. Never log it.
 */
data class AuthorizationRequest(
    val projectId: String,
    val artifact: ArtifactCoordinates,
    val credentials: Map<String, String>,
    val clientInfo: Map<String, String>,
    val remoteAddress: String?,
    val headers: Map<String, List<String>>,
)

/** Decision of an [AuthorizationProvider]. */
sealed interface AuthorizationResult {
    /**
     * Grant access.
     *
     * @param subject who was authorized (for logs and the `subject` attribute), never a secret
     * @param attributes non-sensitive extra fields returned to the client
     */
    data class Allow(val subject: String? = null, val attributes: Map<String, String> = emptyMap()) : AuthorizationResult

    data class Deny(val reason: String, val attributes: Map<String, String> = emptyMap()) : AuthorizationResult
}

/**
 * The single extension point for user authorization. Operators implement this against their own
 * accounts, license database, Discord, website sessions, device ids, or any combination, without
 * touching streaming, encryption or artifact management.
 *
 * ```kotlin
 * class MyProvider(private val db: LicenseDb) : AuthorizationProvider {
 *     override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
 *         val key = request.credentials["licenseKey"] ?: return AuthorizationResult.Deny("license key required")
 *         val license = db.find(key) ?: return AuthorizationResult.Deny("unknown license")
 *         return AuthorizationResult.Allow(subject = license.owner)
 *     }
 * }
 * ```
 */
interface AuthorizationProvider {
    suspend fun authorize(request: AuthorizationRequest): AuthorizationResult
}

/** Creates a provider from a lambda. */
inline fun AuthorizationProvider(crossinline block: suspend (AuthorizationRequest) -> AuthorizationResult): AuthorizationProvider =
    object : AuthorizationProvider {
        override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult = block(request)
    }
