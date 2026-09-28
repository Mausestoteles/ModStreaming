package dev.modstreaming.backend.api

import kotlinx.serialization.Serializable

/** Wire types of protocol version 1 (mirrors `dev.modstreaming.core.client.JsonWire`). */

@Serializable
data class ArtifactDto(val modVersion: String, val minecraftVersion: String, val loader: String = "fabric")

@Serializable
data class VerifyRequestDto(
    val protocolVersion: Int = 1,
    val projectId: String,
    val artifact: ArtifactDto,
    val credentials: Map<String, String> = emptyMap(),
    val client: Map<String, String> = emptyMap(),
)

@Serializable
data class VerifyResponseDto(
    val decision: String,
    val reason: String? = null,
    val downloadToken: String? = null,
    val key: String? = null,
    val artifactId: String? = null,
    val expiresAt: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    companion object {
        fun deny(reason: String, attributes: Map<String, String> = emptyMap()) =
            VerifyResponseDto(decision = "DENY", reason = reason, attributes = attributes)
    }
}

@Serializable
data class PublishRequestDto(val protocolVersion: Int = 1, val artifact: String, val key: String)

@Serializable
data class ArtifactSummaryDto(
    val artifactId: String,
    val projectId: String,
    val modVersion: String,
    val minecraftVersion: String,
    val loader: String,
    val createdAt: String,
    val contentSize: Long,
    val services: List<String>,
)

@Serializable
data class PublishResponseDto(
    val artifactId: String,
    val projectId: String,
    val modVersion: String,
    val minecraftVersion: String,
    val loader: String,
    val replaced: Boolean,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class ArtifactListDto(val artifacts: List<ArtifactSummaryDto>)

@Serializable
data class ErrorDto(val error: String)
