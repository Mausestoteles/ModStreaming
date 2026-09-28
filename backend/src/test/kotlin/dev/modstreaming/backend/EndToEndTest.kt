package dev.modstreaming.backend

import dev.modstreaming.api.Bridge
import dev.modstreaming.api.DefaultBridgeProvider
import dev.modstreaming.api.auth.CredentialsProvider
import dev.modstreaming.backend.auth.LicenseKeyAuthorizationProvider
import dev.modstreaming.backend.e2e.HelloService
import dev.modstreaming.core.config.StreamConfig
import dev.modstreaming.core.crypto.Ed25519
import dev.modstreaming.core.log.StreamLogger
import dev.modstreaming.core.publish.ArtifactPublisher
import dev.modstreaming.core.runtime.LoadStatus
import dev.modstreaming.core.runtime.StreamingRuntime
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full chain against a real HTTP backend:
 *
 * `@Streamed` impl → package (sign + encrypt) → upload → client verification request →
 * operator-defined authorization → ALLOW → download → signature check → decryption →
 * in-memory class loader → Bridge → public hook.
 */
class EndToEndTest {

    private lateinit var server: io.ktor.server.engine.EmbeddedServer<*, *>
    private var port: Int = 0
    private val bridge = DefaultBridgeProvider()

    @BeforeTest
    fun start() {
        val provider = LicenseKeyAuthorizationProvider(mapOf("example-mod" to listOf("E2E-LICENSE")))
        val deps = TestSupport.deps(authorization = provider)
        server = embeddedServer(CIO, port = 0, host = "127.0.0.1") { modStreamingBackend(deps) }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        Bridge.setProvider(bridge)
    }

    @AfterTest
    fun stop() {
        server.stop(100, 1000)
        Bridge.setProvider(DefaultBridgeProvider())
    }

    /** The public hook as it would live in the mod: no-op until the service arrives. */
    private fun hook(name: String): String =
        Bridge.get(HelloService::class.java).map { it.hello(name) }.orElse("no-op")

    private fun config(): StreamConfig = StreamConfig.builder()
        .endpoint("http://127.0.0.1:$port")
        .coordinates(TestSupport.coordinates)
        .publicKey(Ed25519.encodePublicKey(TestSupport.keys.public))
        .service(HelloService::class.java.name)
        .build()

    @Test
    fun `streamed implementation reaches the public hook only after ALLOW`() {
        // publish exactly like the Gradle task does
        val packaged = TestSupport.packaged()
        val publishResult = ArtifactPublisher(URI.create("http://127.0.0.1:$port")).publish(
            packaged.containerBytes(), packaged.key(), TestSupport.PUBLISHER_TOKEN,
        )
        assertEquals(packaged.manifest().artifactId(), publishResult.artifactId())

        assertEquals("no-op", hook("Steve"))

        // denied: hooks stay no-ops
        val denied = StreamingRuntime.builder(config())
            .credentials(CredentialsProvider.of("licenseKey", "WRONG"))
            .bridge(bridge)
            .logger(StreamLogger.silent())
            .parentClassLoader(javaClass.classLoader)
            .build()
            .load()
        assertEquals(LoadStatus.DENIED, denied.status)
        assertTrue(denied.message().contains("not valid"))
        assertEquals("no-op", hook("Steve"))
        assertFalse(Bridge.isAvailable(HelloService::class.java))

        // allowed: verification → download → verify → decrypt → in-memory loader → Bridge
        val runtime = StreamingRuntime.builder(config())
            .credentials(CredentialsProvider.of("licenseKey", "E2E-LICENSE"))
            .bridge(bridge)
            .logger(StreamLogger.silent())
            .parentClassLoader(javaClass.classLoader)
            .build()
        val loaded = runtime.load()
        assertEquals(LoadStatus.LOADED, loaded.status, loaded.message())
        assertEquals(packaged.manifest().artifactId(), loaded.artifactId())
        assertEquals(listOf(HelloService::class.java.name), loaded.services())
        assertTrue(loaded.attributes()["subject"]!!.startsWith("license:"))

        assertEquals("Hello, Steve! (streamed)", hook("Steve"))
        val impl = Bridge.get(HelloService::class.java).orElseThrow()
        assertEquals(loaded.loader().orElseThrow(), impl.javaClass.classLoader, "implementation lives in the in-memory loader")
        assertTrue(impl.javaClass.classLoader !== javaClass.classLoader)

        runtime.unload()
        assertEquals("no-op", hook("Steve"))
    }

    @Test
    fun `backend without the artifact yields a clean deny`() {
        val result = StreamingRuntime.builder(config())
            .credentials(CredentialsProvider.of("licenseKey", "E2E-LICENSE"))
            .bridge(bridge)
            .logger(StreamLogger.silent())
            .build()
            .load()
        assertEquals(LoadStatus.DENIED, result.status)
        assertTrue(result.message().contains("no artifact available"))
    }
}
