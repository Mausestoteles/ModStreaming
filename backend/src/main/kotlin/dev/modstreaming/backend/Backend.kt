package dev.modstreaming.backend

import dev.modstreaming.backend.api.ErrorDto
import dev.modstreaming.backend.api.modStreamingRoutes
import dev.modstreaming.backend.auth.AllowAllAuthorizationProvider
import dev.modstreaming.backend.auth.AuthorizationProvider
import dev.modstreaming.backend.auth.DenyAllAuthorizationProvider
import dev.modstreaming.backend.auth.LicenseKeyAuthorizationProvider
import dev.modstreaming.backend.config.BackendConfig
import dev.modstreaming.backend.publisher.PublisherAuthenticator
import dev.modstreaming.backend.publisher.StaticTokenPublisherAuthenticator
import dev.modstreaming.backend.storage.ArtifactStore
import dev.modstreaming.backend.storage.FileSystemArtifactStore
import dev.modstreaming.backend.token.DownloadTokenService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * Everything the HTTP layer needs. Operators compose their own instance (custom
 * [AuthorizationProvider], [PublisherAuthenticator] or [ArtifactStore]) and pass it to
 * [modStreamingBackend]; streaming, encryption and artifact handling stay untouched.
 */
class BackendDependencies(
    val config: BackendConfig,
    val store: ArtifactStore,
    val authorization: AuthorizationProvider,
    val publishers: PublisherAuthenticator,
    val tokens: DownloadTokenService = DownloadTokenService(
        Duration.ofSeconds(config.downloadTokenTtlSeconds),
        config.downloadTokenMaxUses,
    ),
    val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        private val log = LoggerFactory.getLogger("ModStreaming.Backend")

        /** Wires the reference implementations according to [config]. */
        fun fromConfig(config: BackendConfig, env: Map<String, String> = System.getenv()): BackendDependencies {
            val provider: AuthorizationProvider = when (config.authorization.provider.lowercase()) {
                "allow-all", "allow" -> {
                    log.warn("authorization provider is allow-all: every client is granted access")
                    AllowAllAuthorizationProvider
                }
                "deny-all", "deny" -> DenyAllAuthorizationProvider
                "license-key", "license" -> LicenseKeyAuthorizationProvider.fromConfig(config.authorization)
                else -> throw IllegalArgumentException(
                    "unknown authorization provider '${config.authorization.provider}' (built-in: allow-all, deny-all, license-key)",
                )
            }
            val tokens = config.publisherTokens(env)
            if (tokens.isEmpty()) {
                log.warn("no publisher tokens configured: uploads will be rejected")
            }
            return BackendDependencies(
                config = config,
                store = FileSystemArtifactStore(Path.of(config.storageDir)),
                authorization = provider,
                publishers = StaticTokenPublisherAuthenticator(tokens),
            )
        }
    }
}

/** Ktor module: installs JSON, error mapping, request logging and the routes. */
fun Application.modStreamingBackend(deps: BackendDependencies) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("malformed request: ${cause.message}"))
        }
        exception<kotlinx.serialization.SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("malformed request: ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            LoggerFactory.getLogger("ModStreaming.Backend").error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }
    install(CallLogging)
    routing {
        modStreamingRoutes(deps)
    }
}
