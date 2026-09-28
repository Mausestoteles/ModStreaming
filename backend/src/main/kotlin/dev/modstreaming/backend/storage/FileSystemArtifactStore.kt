package dev.modstreaming.backend.storage

import dev.modstreaming.api.auth.ArtifactCoordinates
import dev.modstreaming.core.artifact.ArtifactManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Persists artifacts as
 * `<root>/<projectId>/<modVersion>/<minecraftVersion>/<loader>/{artifact.msa, artifact.key, manifest.json}`.
 * Coordinates are validated by [ArtifactCoordinates], so they are safe path segments.
 */
class FileSystemArtifactStore(private val root: Path) : ArtifactStore {

    private fun dir(c: ArtifactCoordinates): Path =
        root.resolve(c.projectId()).resolve(c.modVersion()).resolve(c.minecraftVersion()).resolve(c.loader())

    override suspend fun put(artifact: StoredArtifact): Boolean = withContext(Dispatchers.IO) {
        val dir = dir(artifact.coordinates)
        val replaced = dir.resolve(ARTIFACT).exists()
        Files.createDirectories(dir)
        writeAtomically(dir.resolve(ARTIFACT), artifact.container)
        writeAtomically(dir.resolve(KEY), Base64.getEncoder().encodeToString(artifact.key).toByteArray())
        writeAtomically(dir.resolve(MANIFEST), artifact.manifest.toJson().toByteArray())
        restrictPermissions(dir.resolve(KEY))
        replaced
    }

    override suspend fun get(coordinates: ArtifactCoordinates): StoredArtifact? = withContext(Dispatchers.IO) {
        read(dir(coordinates), coordinates)
    }

    override suspend fun list(projectId: String?): List<StoredArtifact> = withContext(Dispatchers.IO) {
        if (!root.isDirectory()) return@withContext emptyList()
        val out = ArrayList<StoredArtifact>()
        val projects = if (projectId != null) listOf(root.resolve(projectId)) else root.listDirectoryEntries()
        for (project in projects.filter { it.isDirectory() }) {
            for (version in project.listDirectoryEntries().filter { it.isDirectory() }) {
                for (mc in version.listDirectoryEntries().filter { it.isDirectory() }) {
                    for (loader in mc.listDirectoryEntries().filter { it.isDirectory() }) {
                        val coordinates = runCatching {
                            ArtifactCoordinates(project.name, version.name, mc.name, loader.name)
                        }.getOrNull() ?: continue
                        read(loader, coordinates)?.let(out::add)
                    }
                }
            }
        }
        out.sortedBy { it.coordinates.key() }
    }

    override suspend fun delete(coordinates: ArtifactCoordinates): Boolean = withContext(Dispatchers.IO) {
        val dir = dir(coordinates)
        if (!dir.exists()) return@withContext false
        dir.listDirectoryEntries().forEach { Files.deleteIfExists(it) }
        Files.deleteIfExists(dir)
        true
    }

    private fun read(dir: Path, coordinates: ArtifactCoordinates): StoredArtifact? {
        val artifact = dir.resolve(ARTIFACT)
        val key = dir.resolve(KEY)
        val manifest = dir.resolve(MANIFEST)
        if (!artifact.exists() || !key.exists() || !manifest.exists()) return null
        return StoredArtifact(
            coordinates,
            ArtifactManifest.fromJson(Files.readString(manifest)),
            Files.readAllBytes(artifact),
            Base64.getDecoder().decode(Files.readString(key).trim()),
            Files.getLastModifiedTime(artifact).toInstant().let { Instant.ofEpochMilli(it.toEpochMilli()) },
        )
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val tmp = target.resolveSibling(target.name + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrictPermissions(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private companion object {
        const val ARTIFACT = "artifact.msa"
        const val KEY = "artifact.key"
        const val MANIFEST = "manifest.json"
    }
}
