package dev.modstreaming.backend

import dev.modstreaming.backend.api.ErrorDto
import dev.modstreaming.backend.api.PublishRequestDto
import dev.modstreaming.core.artifact.ArtifactContainer
import dev.modstreaming.core.artifact.ArtifactPackager
import dev.modstreaming.core.crypto.Ed25519
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TamperedUploadTest {

    private suspend fun io.ktor.client.HttpClient.publishRaw(container: ByteArray, key: ByteArray) =
        post("/api/v1/publish") {
            header(HttpHeaders.Authorization, "Bearer ${TestSupport.PUBLISHER_TOKEN}")
            contentType(ContentType.Application.Json)
            setBody(PublishRequestDto(artifact = Base64.getEncoder().encodeToString(container), key = Base64.getEncoder().encodeToString(key)))
        }

    @Test
    fun `tampered payload is rejected`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        val packaged = TestSupport.packaged()
        val payload = packaged.container().payload().clone()
        payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 0x01).toByte()
        val tampered = ArtifactContainer(packaged.container().manifestBytes(), packaged.container().signature(), payload)
        val response = client.publishRaw(tampered.toBytes(), packaged.key())
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<ErrorDto>().error.contains("hash"))
    }

    @Test
    fun `artifact signed with a foreign key is rejected`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        val foreign = TestSupport.packaged(keys = Ed25519.generateKeyPair())
        val response = client.publishRaw(foreign.containerBytes(), foreign.key())
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<ErrorDto>().error.contains("signature"))
    }

    @Test
    fun `wrong key is rejected`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        val packaged = TestSupport.packaged()
        val wrongLength = client.publishRaw(packaged.containerBytes(), ByteArray(16))
        assertEquals(HttpStatusCode.BadRequest, wrongLength.status)
        val wrongKey = client.publishRaw(packaged.containerBytes(), ByteArray(32))
        assertEquals(HttpStatusCode.BadRequest, wrongKey.status)
        assertTrue(wrongKey.body<ErrorDto>().error.contains("decrypt"))
    }

    @Test
    fun `unregistered projects are refused unless allowed`() = testApplication {
        application { modStreamingBackend(TestSupport.deps(config = TestSupport.config(publicKey = null))) }
        val client = jsonClient()
        val packaged = TestSupport.packaged()
        assertEquals(HttpStatusCode.Forbidden, client.publishRaw(packaged.containerBytes(), packaged.key()).status)
    }

    @Test
    fun `unregistered projects can be allowed for development`() = testApplication {
        application { modStreamingBackend(TestSupport.deps(config = TestSupport.config(publicKey = null, allowUnregistered = true))) }
        val client = jsonClient()
        val packaged: ArtifactPackager.Packaged = TestSupport.packaged()
        assertEquals(HttpStatusCode.Created, client.publishRaw(packaged.containerBytes(), packaged.key()).status)
    }

    @Test
    fun `garbage container is rejected`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, client.publishRaw(byteArrayOf(1, 2, 3), ByteArray(32)).status)
    }
}
