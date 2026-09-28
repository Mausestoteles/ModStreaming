package dev.modstreaming.backend.publisher

import dev.modstreaming.backend.auth.constantTimeEquals
import dev.modstreaming.backend.auth.fingerprint

/**
 * Who may upload artifacts.
 *
 * @param name label for logs
 * @param projects project ids this publisher may upload for, or `null` for all
 */
data class PublisherIdentity(val name: String, val projects: Set<String>?) {
    fun mayPublish(projectId: String): Boolean = projects == null || projectId in projects
}

/** Authenticates the bearer token of publish requests. Replaceable like the authorization provider. */
interface PublisherAuthenticator {
    fun authenticate(token: String): PublisherIdentity?
}

/** Static token list, e.g. from the config file or the `MODSTREAMING_PUBLISHER_TOKEN` variable. */
class StaticTokenPublisherAuthenticator(tokens: Map<String, Set<String>?>) : PublisherAuthenticator {

    private val entries: List<Pair<String, PublisherIdentity>> = tokens.entries
        .filter { it.key.isNotBlank() }
        .map { (token, projects) -> token to PublisherIdentity("token:" + fingerprint(token), projects) }

    override fun authenticate(token: String): PublisherIdentity? {
        var found: PublisherIdentity? = null
        for ((candidate, identity) in entries) {
            // compare every entry so timing does not reveal which token matched
            if (constantTimeEquals(candidate, token)) {
                found = identity
            }
        }
        return found
    }
}
