package dev.modstreaming.backend.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Backend configuration, loaded from a JSON file (`MODSTREAMING_BACKEND_CONFIG`) with a few
 * environment overrides. Everything here is operator-side; nothing is ever sent to clients.
 */
@Serializable
data class BackendConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Directory for the file system artifact store. */
    val storageDir: String = "backend-data",
    val downloadTokenTtlSeconds: Long = 300,
    val downloadTokenMaxUses: Int = 3,
    /** Accept uploads for projects that are not listed under [projects] (development only). */
    val allowUnregisteredProjects: Boolean = false,
    /** Upper bound for uploaded containers. */
    val maxArtifactBytes: Long = 64L * 1024 * 1024,
    val authorization: AuthorizationConfig = AuthorizationConfig(),
    val projects: Map<String, ProjectConfig> = emptyMap(),
) {
    companion object {
        const val CONFIG_ENV = "MODSTREAMING_BACKEND_CONFIG"
        const val PORT_ENV = "MODSTREAMING_BACKEND_PORT"
        const val STORAGE_ENV = "MODSTREAMING_BACKEND_STORAGE"
        const val PUBLISHER_TOKEN_ENV = "MODSTREAMING_PUBLISHER_TOKEN"
        const val AUTH_PROVIDER_ENV = "MODSTREAMING_AUTH_PROVIDER"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): BackendConfig = json.decodeFromString(serializer(), text)

        /** Loads the config file named by [CONFIG_ENV] (if any) and applies environment overrides. */
        fun load(env: Map<String, String> = System.getenv()): BackendConfig {
            val base = env[CONFIG_ENV]?.let { path -> parse(Files.readString(Path.of(path))) } ?: BackendConfig()
            return base.withEnvironment(env)
        }

        fun fromFile(path: Path): BackendConfig = parse(Files.readString(path))
    }

    fun withEnvironment(env: Map<String, String>): BackendConfig {
        var config = this
        env[PORT_ENV]?.toIntOrNull()?.let { config = config.copy(port = it) }
        env[STORAGE_ENV]?.let { config = config.copy(storageDir = it) }
        env[AUTH_PROVIDER_ENV]?.let { config = config.copy(authorization = config.authorization.copy(provider = it)) }
        return config
    }

    /** Publisher tokens from the config plus the optional global token from the environment. */
    fun publisherTokens(env: Map<String, String> = System.getenv()): Map<String, Set<String>?> {
        val tokens = LinkedHashMap<String, MutableSet<String>?>()
        for ((projectId, project) in projects) {
            for (token in project.publisherTokens) {
                val existing = tokens[token]
                if (existing == null) {
                    tokens[token] = mutableSetOf(projectId)
                } else {
                    existing.add(projectId)
                }
            }
        }
        env[PUBLISHER_TOKEN_ENV]?.takeIf { it.isNotBlank() }?.let { tokens[it] = null }
        return tokens
    }
}

@Serializable
data class ProjectConfig(
    /** Base64 Ed25519 public key; uploads must be signed by the matching private key. */
    val publicKey: String? = null,
    /** Tokens allowed to publish artifacts for this project. */
    val publisherTokens: List<String> = emptyList(),
)

@Serializable
data class AuthorizationConfig(
    /** `allow-all`, `deny-all` or `license-key`. Custom providers are wired in code. */
    val provider: String = "allow-all",
    /** Credential field checked by the license-key provider. */
    val credentialField: String = "licenseKey",
    /** License keys per project for the license-key provider. */
    val licenses: Map<String, List<String>> = emptyMap(),
)
