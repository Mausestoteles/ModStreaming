package dev.modstreaming.backend.auth

import dev.modstreaming.backend.config.AuthorizationConfig
import java.security.MessageDigest

/** Grants every request. Only for local development. */
object AllowAllAuthorizationProvider : AuthorizationProvider {
    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult =
        AuthorizationResult.Allow(subject = "anonymous")
}

/** Refuses every request. */
object DenyAllAuthorizationProvider : AuthorizationProvider {
    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult =
        AuthorizationResult.Deny("streaming is disabled")
}

/**
 * Example provider: a static list of license keys per project. The key is expected in the
 * credential field [credentialField] (default `licenseKey`). Demonstrates the contract; a real
 * deployment would consult a database or an external service instead.
 */
class LicenseKeyAuthorizationProvider(
    private val licenses: Map<String, Collection<String>>,
    private val credentialField: String = "licenseKey",
) : AuthorizationProvider {

    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
        val presented = request.credentials[credentialField]?.trim()
        if (presented.isNullOrEmpty()) {
            return AuthorizationResult.Deny("credential '$credentialField' is required")
        }
        val valid = licenses[request.projectId].orEmpty()
        val match = valid.firstOrNull { constantTimeEquals(it, presented) }
            ?: return AuthorizationResult.Deny("license key is not valid for ${request.projectId}")
        return AuthorizationResult.Allow(subject = "license:" + fingerprint(match))
    }

    companion object {
        fun fromConfig(config: AuthorizationConfig): LicenseKeyAuthorizationProvider =
            LicenseKeyAuthorizationProvider(config.licenses, config.credentialField)
    }
}

/** Combines providers: [allOf] requires every provider to allow, [anyOf] the first allowing one wins. */
class CompositeAuthorizationProvider private constructor(
    private val providers: List<AuthorizationProvider>,
    private val requireAll: Boolean,
) : AuthorizationProvider {

    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
        var lastDeny: AuthorizationResult.Deny? = null
        val subjects = ArrayList<String>()
        val attributes = LinkedHashMap<String, String>()
        for (provider in providers) {
            when (val result = provider.authorize(request)) {
                is AuthorizationResult.Allow -> {
                    if (!requireAll) return result
                    result.subject?.let(subjects::add)
                    attributes.putAll(result.attributes)
                }
                is AuthorizationResult.Deny -> {
                    if (requireAll) return result
                    lastDeny = result
                }
            }
        }
        return if (requireAll) {
            AuthorizationResult.Allow(subjects.joinToString("+").ifEmpty { null }, attributes)
        } else {
            lastDeny ?: AuthorizationResult.Deny("no authorization provider configured")
        }
    }

    companion object {
        fun allOf(vararg providers: AuthorizationProvider) = CompositeAuthorizationProvider(providers.toList(), true)
        fun anyOf(vararg providers: AuthorizationProvider) = CompositeAuthorizationProvider(providers.toList(), false)
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/** Short, non-reversible identifier of a secret for logs. */
internal fun fingerprint(secret: String): String =
    MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        .take(6).joinToString("") { "%02x".format(it) }
