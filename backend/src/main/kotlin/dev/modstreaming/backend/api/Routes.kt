package dev.modstreaming.backend.api

import dev.modstreaming.api.auth.ArtifactCoordinates
import dev.modstreaming.backend.BackendDependencies
import dev.modstreaming.backend.auth.AuthorizationRequest
import dev.modstreaming.backend.auth.AuthorizationResult
import dev.modstreaming.backend.storage.StoredArtifact
import dev.modstreaming.core.artifact.ArtifactContainer
import dev.modstreaming.core.artifact.ArtifactException
import dev.modstreaming.core.artifact.ArtifactVerifier
import dev.modstreaming.core.crypto.AesGcm
import dev.modstreaming.core.crypto.Ed25519
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("ModStreaming.Backend")

/**
 * HTTP surface of the reference backend.
 *
 * ```
 * POST /api/v1/verify              client verification (any auth logic via AuthorizationProvider)
 * GET  /api/v1/artifacts/download  Authorization: Bearer <downloadToken>
 * POST /api/v1/publish             Authorization: Bearer <publisherToken>
 * GET  /api/v1/artifacts           Authorization: Bearer <publisherToken>
 * GET  /healthz
 * ```
 */
fun Route.modStreamingRoutes(deps: BackendDependencies) {
    get("/healthz") { call.respondText("ok") }

    route("/api/v1") {
        post("/verify") { handleVerify(call, deps) }
        get("/artifacts/download") { handleDownload(call, deps) }
        post("/publish") { handlePublish(call, deps) }
        get("/artifacts") { handleList(call, deps) }
    }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().takeIf { it.isNotEmpty() }
}

private suspend fun handleVerify(call: ApplicationCall, deps: BackendDependencies) {
    val body = call.receive<VerifyRequestDto>()
    if (body.protocolVersion != 1) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("unsupported protocol version ${body.protocolVersion}"))
        return
    }
    val coordinates = try {
        ArtifactCoordinates(body.projectId, body.artifact.modVersion, body.artifact.minecraftVersion, body.artifact.loader)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid artifact coordinates: ${e.message}"))
        return
    }
    val remote = runCatching { call.request.origin.remoteHost }.getOrNull()
    val request = AuthorizationRequest(
        projectId = coordinates.projectId(),
        artifact = coordinates,
        credentials = body.credentials,
        clientInfo = body.client,
        remoteAddress = remote,
        headers = call.request.headers.entries().associate { it.key to it.value },
    )

    // The operator's logic decides. Credentials are never logged.
    val decision = try {
        deps.authorization.authorize(request)
    } catch (e: Exception) {
        log.error("authorization provider failed for ${coordinates.key()}", e)
        call.respond(HttpStatusCode.InternalServerError, ErrorDto("authorization failed"))
        return
    }
    when (decision) {
        is AuthorizationResult.Deny -> {
            log.info("DENY {} from {}: {}", coordinates.key(), remote, decision.reason)
            call.respond(HttpStatusCode.Forbidden, VerifyResponseDto.deny(decision.reason, decision.attributes))
        }
        is AuthorizationResult.Allow -> {
            val artifact = deps.store.get(coordinates)
            if (artifact == null) {
                log.info("ALLOW {} for {} but no artifact is stored", coordinates.key(), decision.subject)
                call.respond(HttpStatusCode.NotFound, VerifyResponseDto.deny("no artifact available for ${coordinates.key()}"))
                return
            }
            val grant = deps.tokens.issue(coordinates, artifact.artifactId, decision.subject)
            log.info("ALLOW {} for {} from {} (artifact {})", coordinates.key(), decision.subject, remote, artifact.artifactId)
            val attributes = LinkedHashMap(decision.attributes)
            decision.subject?.let { attributes.putIfAbsent("subject", it) }
            call.respond(
                HttpStatusCode.OK,
                VerifyResponseDto(
                    decision = "ALLOW",
                    downloadToken = grant.token,
                    key = Base64.getEncoder().encodeToString(artifact.key),
                    artifactId = artifact.artifactId,
                    expiresAt = grant.expiresAt.toString(),
                    attributes = attributes,
                ),
            )
        }
    }
}

private suspend fun handleDownload(call: ApplicationCall, deps: BackendDependencies) {
    val token = call.bearerToken()
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("download token required"))
        return
    }
    val grant = deps.tokens.redeem(token)
    if (grant == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("download token is invalid, expired or exhausted"))
        return
    }
    val artifact = deps.store.get(grant.coordinates)
    if (artifact == null || artifact.artifactId != grant.artifactId) {
        call.respond(HttpStatusCode.NotFound, ErrorDto("artifact is no longer available"))
        return
    }
    log.info("download {} by {}", grant.coordinates.key(), grant.subject)
    call.response.headers.append("X-ModStreaming-Artifact-Id", artifact.artifactId)
    call.respondBytes(artifact.container, ContentType.Application.OctetStream)
}

private suspend fun handlePublish(call: ApplicationCall, deps: BackendDependencies) {
    val token = call.bearerToken()
    val publisher = token?.let(deps.publishers::authenticate)
    if (publisher == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("publisher token is missing or invalid"))
        return
    }
    val body = call.receive<PublishRequestDto>()
    val containerBytes = try {
        Base64.getDecoder().decode(body.artifact)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("artifact is not valid Base64"))
        return
    }
    val key = try {
        Base64.getDecoder().decode(body.key)
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("key is not valid Base64"))
        return
    }
    if (key.size != AesGcm.KEY_BYTES) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("key must be ${AesGcm.KEY_BYTES} bytes"))
        return
    }

    val container: ArtifactContainer
    val manifest = try {
        container = ArtifactContainer.fromBytes(containerBytes, deps.config.maxArtifactBytes)
        container.parseManifest()
    } catch (e: ArtifactException) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid artifact: ${e.message}"))
        return
    }
    val coordinates = manifest.coordinates()
    if (!publisher.mayPublish(coordinates.projectId())) {
        log.warn("publisher {} may not publish for {}", publisher.name, coordinates.projectId())
        call.respond(HttpStatusCode.Forbidden, ErrorDto("publisher is not allowed to publish for ${coordinates.projectId()}"))
        return
    }
    val project = deps.config.projects[coordinates.projectId()]
    val publicKey = project?.publicKey
    if (publicKey == null && !deps.config.allowUnregisteredProjects) {
        call.respond(HttpStatusCode.Forbidden, ErrorDto("project ${coordinates.projectId()} is not registered on this backend"))
        return
    }
    try {
        if (publicKey != null) {
            ArtifactVerifier.forPublicKey(Ed25519.decodePublicKey(publicKey)).verify(container, coordinates)
        } else {
            // Unregistered project: still require the payload hash to match the signed manifest.
            ArtifactVerifier({ _, _ -> true }, null, null).verify(container, coordinates)
        }
    } catch (e: ArtifactException) {
        log.warn("rejected upload for {} by {}: {}", coordinates.key(), publisher.name, e.message)
        call.respond(HttpStatusCode.BadRequest, ErrorDto("artifact rejected: ${e.message}"))
        return
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.InternalServerError, ErrorDto("configured public key for ${coordinates.projectId()} is invalid"))
        return
    }
    // Sanity check: the uploaded key must actually decrypt the payload.
    try {
        ArtifactVerifier({ _, _ -> true }, null, null).verify(container, coordinates).decrypt(key)
    } catch (e: ArtifactException) {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("key does not decrypt the artifact"))
        return
    }

    val replaced = deps.store.put(StoredArtifact(coordinates, manifest, containerBytes, key, deps.clock.instant()))
    log.info("published {} (artifact {}, {} bytes) by {}{}", coordinates.key(), manifest.artifactId(),
        containerBytes.size, publisher.name, if (replaced) ", replaced" else "")
    call.respond(
        HttpStatusCode.Created,
        PublishResponseDto(
            artifactId = manifest.artifactId(),
            projectId = coordinates.projectId(),
            modVersion = coordinates.modVersion(),
            minecraftVersion = coordinates.minecraftVersion(),
            loader = coordinates.loader(),
            replaced = replaced,
        ),
    )
}

private suspend fun handleList(call: ApplicationCall, deps: BackendDependencies) {
    val token = call.bearerToken()
    val publisher = token?.let(deps.publishers::authenticate)
    if (publisher == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("publisher token is missing or invalid"))
        return
    }
    val projectId = call.request.queryParameters["projectId"]
    val artifacts = deps.store.list(projectId)
        .filter { publisher.mayPublish(it.coordinates.projectId()) }
        .map {
            ArtifactSummaryDto(
                artifactId = it.artifactId,
                projectId = it.coordinates.projectId(),
                modVersion = it.coordinates.modVersion(),
                minecraftVersion = it.coordinates.minecraftVersion(),
                loader = it.coordinates.loader(),
                createdAt = it.manifest.createdAt().toString(),
                contentSize = it.manifest.contentSize(),
                services = it.manifest.services().map { b -> b.service() },
            )
        }
    call.respond(ArtifactListDto(artifacts))
}
