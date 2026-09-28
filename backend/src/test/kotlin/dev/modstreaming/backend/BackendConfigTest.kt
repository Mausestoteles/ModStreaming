package dev.modstreaming.backend

import dev.modstreaming.backend.config.BackendConfig
import dev.modstreaming.backend.publisher.StaticTokenPublisherAuthenticator
import dev.modstreaming.backend.storage.FileSystemArtifactStore
import dev.modstreaming.backend.token.DownloadTokenService
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendConfigTest {

    @Test
    fun `example config parses and env overrides apply`() {
        val example = Path.of("backend.example.json").takeIf { Files.exists(it) } ?: Path.of("../backend/backend.example.json")
        val config = BackendConfig.fromFile(example)
        assertEquals("license-key", config.authorization.provider)
        assertEquals(listOf("EXAMPLE-LICENSE-KEY-1", "EXAMPLE-LICENSE-KEY-2"), config.authorization.licenses["example-mod"])
        assertEquals(8080, config.port)
        val overridden = config.withEnvironment(mapOf(BackendConfig.PORT_ENV to "9090", BackendConfig.AUTH_PROVIDER_ENV to "deny-all", BackendConfig.STORAGE_ENV to "/tmp/x"))
        assertEquals(9090, overridden.port)
        assertEquals("deny-all", overridden.authorization.provider)
        assertEquals("/tmp/x", overridden.storageDir)
        val tokens = config.publisherTokens(mapOf(BackendConfig.PUBLISHER_TOKEN_ENV to "global"))
        assertNull(tokens["global"], "env token is valid for every project")
        assertEquals(setOf("example-mod"), tokens.keys.first { it != "global" }.let { tokens[it] })
    }

    @Test
    fun `publisher authenticator maps tokens to identities`() {
        val auth = StaticTokenPublisherAuthenticator(mapOf("t1" to setOf("a"), "t2" to null))
        assertTrue(auth.authenticate("t1")!!.mayPublish("a"))
        assertFalse(auth.authenticate("t1")!!.mayPublish("b"))
        assertTrue(auth.authenticate("t2")!!.mayPublish("anything"))
        assertNull(auth.authenticate("t3"))
        assertFalse(auth.authenticate("t1")!!.name.contains("t1"), "identity name must not contain the token")
    }

    @Test
    fun `download tokens expire and are limited`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val service = DownloadTokenService(Duration.ofMinutes(5), 2, clock)
        val grant = service.issue(TestSupport.coordinates, "artifact", "subject")
        assertEquals(43, grant.token.length)
        assertNotNull(service.redeem(grant.token))
        assertNotNull(service.redeem(grant.token))
        assertNull(service.redeem(grant.token), "max uses reached")
        val second = service.issue(TestSupport.coordinates, "artifact", null)
        now = now.plus(Duration.ofMinutes(6))
        assertNull(service.redeem(second.token), "expired")
        assertEquals(0, service.size)
    }

    @Test
    fun `file system store round trips`() = runTest {
        val dir = Files.createTempDirectory("modstreaming-store")
        val store = FileSystemArtifactStore(dir)
        val packaged = TestSupport.packaged()
        val artifact = dev.modstreaming.backend.storage.StoredArtifact(
            TestSupport.coordinates, packaged.manifest(), packaged.containerBytes(), packaged.key(), Instant.now(),
        )
        assertFalse(store.put(artifact))
        assertTrue(store.put(artifact), "second put replaces")
        val loaded = store.get(TestSupport.coordinates)
        assertNotNull(loaded)
        assertContentEquals(packaged.containerBytes(), loaded.container)
        assertContentEquals(packaged.key(), loaded.key)
        assertEquals(packaged.manifest(), loaded.manifest)
        assertEquals(1, store.list().size)
        assertEquals(1, store.list("example-mod").size)
        assertEquals(0, store.list("other").size)
        assertTrue(store.delete(TestSupport.coordinates))
        assertNull(store.get(TestSupport.coordinates))
        assertTrue(Files.exists(dir.resolve("example-mod/1.0.0/1.21.1")))
    }
}
