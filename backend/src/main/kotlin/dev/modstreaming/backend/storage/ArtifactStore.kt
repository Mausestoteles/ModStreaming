package dev.modstreaming.backend.storage

import dev.modstreaming.api.auth.ArtifactCoordinates
import dev.modstreaming.core.artifact.ArtifactManifest
import java.time.Instant

/**
 * A stored artifact: the signed, encrypted container plus the key that decrypts it. The key is
 * only handed to clients after a successful authorization.
 */
class StoredArtifact(
    val coordinates: ArtifactCoordinates,
    val manifest: ArtifactManifest,
    val container: ByteArray,
    val key: ByteArray,
    val storedAt: Instant,
) {
    val artifactId: String get() = manifest.artifactId()
}

/** Storage abstraction; the reference implementations are in-memory and file system based. */
interface ArtifactStore {
    /** Stores [artifact]; returns `true` if an artifact with the same coordinates was replaced. */
    suspend fun put(artifact: StoredArtifact): Boolean

    suspend fun get(coordinates: ArtifactCoordinates): StoredArtifact?

    suspend fun list(projectId: String? = null): List<StoredArtifact>

    suspend fun delete(coordinates: ArtifactCoordinates): Boolean
}
