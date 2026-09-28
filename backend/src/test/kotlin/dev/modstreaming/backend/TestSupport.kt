package dev.modstreaming.backend

import dev.modstreaming.api.ServiceBinding
import dev.modstreaming.api.auth.ArtifactCoordinates
import dev.modstreaming.backend.auth.AllowAllAuthorizationProvider
import dev.modstreaming.backend.auth.AuthorizationProvider
import dev.modstreaming.backend.config.AuthorizationConfig
import dev.modstreaming.backend.config.BackendConfig
import dev.modstreaming.backend.config.ProjectConfig
import dev.modstreaming.backend.e2e.HelloService
import dev.modstreaming.backend.e2e.streamed.StreamedHello
import dev.modstreaming.backend.publisher.StaticTokenPublisherAuthenticator
import dev.modstreaming.backend.storage.ArtifactStore
import dev.modstreaming.backend.storage.InMemoryArtifactStore
import dev.modstreaming.backend.token.DownloadTokenService
import dev.modstreaming.core.artifact.ArtifactPackager
import dev.modstreaming.core.crypto.Ed25519
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.time.Clock
import java.time.Duration
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

object TestSupport {
    const val PUBLISHER_TOKEN = "publisher-token-abc"
    const val OTHER_PUBLISHER_TOKEN = "publisher-token-other"

    val keys: KeyPair = Ed25519.generateKeyPair()
    val coordinates = ArtifactCoordinates("example-mod", "1.0.0", "1.21.1", "fabric")
    val bindings = listOf(ServiceBinding(HelloService::class.java.name, StreamedHello::class.java.name))

    fun classBytes(clazz: Class<*>): ByteArray {
        val path = clazz.name.replace('.', '/') + ".class"
        return clazz.classLoader.getResourceAsStream(path)!!.use { it.readAllBytes() }
    }

    /** A jar with only the streamed implementation (the interface stays on the public side). */
    fun streamedJar(): ByteArray {
        val bos = ByteArrayOutputStream()
        JarOutputStream(bos).use { jar ->
            jar.putNextEntry(JarEntry(StreamedHello::class.java.name.replace('.', '/') + ".class"))
            jar.write(classBytes(StreamedHello::class.java))
            jar.closeEntry()
        }
        return bos.toByteArray()
    }

    fun packaged(keys: KeyPair = this.keys, coordinates: ArtifactCoordinates = this.coordinates): ArtifactPackager.Packaged =
        ArtifactPackager().pack(streamedJar(), coordinates, bindings, keys.private, keys.public)

    fun config(
        publicKey: String? = Ed25519.encodePublicKey(keys.public),
        allowUnregistered: Boolean = false,
        maxUses: Int = 3,
        authorization: AuthorizationConfig = AuthorizationConfig(provider = "allow-all"),
    ): BackendConfig = BackendConfig(
        downloadTokenMaxUses = maxUses,
        allowUnregisteredProjects = allowUnregistered,
        authorization = authorization,
        projects = mapOf(
            "example-mod" to ProjectConfig(publicKey = publicKey, publisherTokens = listOf(PUBLISHER_TOKEN)),
            "other-mod" to ProjectConfig(publicKey = publicKey, publisherTokens = listOf(OTHER_PUBLISHER_TOKEN)),
        ),
    )

    fun deps(
        authorization: AuthorizationProvider = AllowAllAuthorizationProvider,
        config: BackendConfig = config(),
        store: ArtifactStore = InMemoryArtifactStore(),
        clock: Clock = Clock.systemUTC(),
        tokenTtl: Duration = Duration.ofMinutes(5),
    ): BackendDependencies = BackendDependencies(
        config = config,
        store = store,
        authorization = authorization,
        publishers = StaticTokenPublisherAuthenticator(config.publisherTokens(emptyMap())),
        tokens = DownloadTokenService(tokenTtl, config.downloadTokenMaxUses, clock),
        clock = clock,
    )
}
