package dev.modstreaming.backend.storage

import dev.modstreaming.api.auth.ArtifactCoordinates
import java.util.concurrent.ConcurrentHashMap

/** Non-persistent store for tests and demos. */
class InMemoryArtifactStore : ArtifactStore {
    private val artifacts = ConcurrentHashMap<ArtifactCoordinates, StoredArtifact>()

    override suspend fun put(artifact: StoredArtifact): Boolean = artifacts.put(artifact.coordinates, artifact) != null

    override suspend fun get(coordinates: ArtifactCoordinates): StoredArtifact? = artifacts[coordinates]

    override suspend fun list(projectId: String?): List<StoredArtifact> =
        artifacts.values.filter { projectId == null || it.coordinates.projectId() == projectId }
            .sortedBy { it.coordinates.key() }

    override suspend fun delete(coordinates: ArtifactCoordinates): Boolean = artifacts.remove(coordinates) != null
}
