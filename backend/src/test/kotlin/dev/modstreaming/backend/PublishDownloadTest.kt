package dev.modstreaming.backend

import dev.modstreaming.backend.api.ArtifactListDto
import dev.modstreaming.backend.api.ArtifactDto
import dev.modstreaming.backend.api.PublishRequestDto
import dev.modstreaming.backend.api.PublishResponseDto
import dev.modstreaming.backend.api.VerifyRequestDto
import dev.modstreaming.backend.api.VerifyResponseDto
import dev.modstreaming.backend.storage.InMemoryArtifactStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
    install(ContentNegotiation) { json() }
}

suspend fun HttpClient.publish(packaged: dev.modstreaming.core.artifact.ArtifactPackager.Packaged, token: String?): HttpResponse =
    post("/api/v1/publish") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
        setBody(
            PublishRequestDto(
                artifact = Base64.getEncoder().encodeToString(packaged.containerBytes()),
                key = Base64.getEncoder().encodeToString(packaged.key()),
            ),
        )
    }

suspend fun HttpClient.verify(credentials: Map<String, String> = emptyMap(), projectId: String = "example-mod"): HttpResponse =
    post("/api/v1/verify") {
        contentType(ContentType.Application.Json)
        setBody(VerifyRequestDto(projectId = projectId, artifact = ArtifactDto("1.0.0", "1.21.1", "fabric"), credentials = credentials))
    }

suspend fun HttpClient.download(token: String?): HttpResponse =
    get("/api/v1/artifacts/download") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

class PublishDownloadTest {

    @Test
    fun `publisher authentication is required`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        val packaged = TestSupport.packaged()
        assertEquals(HttpStatusCode.Unauthorized, client.publish(packaged, null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.publish(packaged, "wrong-token").status)
        assertEquals(HttpStatusCode.Forbidden, client.publish(packaged, TestSupport.OTHER_PUBLISHER_TOKEN).status,
            "token of another project must not publish for example-mod")
    }

    @Test
    fun `upload verify download round trip`() = testApplication {
        val store = InMemoryArtifactStore()
        val deps = TestSupport.deps(store = store, config = TestSupport.config(maxUses = 2))
        application { modStreamingBackend(deps) }
        val client = jsonClient()
        val packaged = TestSupport.packaged()

        // verification before upload: authorized, but nothing stored
        val missing = client.verify()
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("DENY", missing.body<VerifyResponseDto>().decision)

        val published = client.publish(packaged, TestSupport.PUBLISHER_TOKEN)
        assertEquals(HttpStatusCode.Created, published.status, published.bodyAsText())
        val publishBody = published.body<PublishResponseDto>()
        assertEquals(packaged.manifest().artifactId(), publishBody.artifactId)
        assertEquals("example-mod", publishBody.projectId)
        assertFalse(publishBody.replaced)
        assertNotNull(store.get(TestSupport.coordinates))

        val verified = client.verify(mapOf("token" to "irrelevant-for-allow-all"))
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        val grant = verified.body<VerifyResponseDto>()
        assertEquals("ALLOW", grant.decision)
        assertNotNull(grant.downloadToken)
        assertContentEquals(packaged.key(), Base64.getDecoder().decode(grant.key))
        assertEquals(packaged.manifest().artifactId(), grant.artifactId)
        assertNotNull(grant.expiresAt)
        assertEquals("anonymous", grant.attributes["subject"])
        assertNull(grant.reason)

        val downloaded = client.download(grant.downloadToken)
        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertEquals(ContentType.Application.OctetStream, downloaded.contentType())
        assertContentEquals(packaged.containerBytes(), downloaded.readRawBytes())
        assertEquals(packaged.manifest().artifactId(), downloaded.headers["X-ModStreaming-Artifact-Id"])

        // second use allowed (maxUses = 2), third exhausted
        assertEquals(HttpStatusCode.OK, client.download(grant.downloadToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.download(grant.downloadToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.download("not-a-token").status)
        assertEquals(HttpStatusCode.Unauthorized, client.download(null).status)

        // re-publish replaces
        val again = client.publish(TestSupport.packaged(), TestSupport.PUBLISHER_TOKEN)
        assertEquals(HttpStatusCode.Created, again.status)
        assertTrue(again.body<PublishResponseDto>().replaced)

        // old grants no longer match the replaced artifact
        val staleGrant = client.verify().body<VerifyResponseDto>()
        assertEquals(HttpStatusCode.OK, client.download(staleGrant.downloadToken).status)
    }

    @Test
    fun `listing requires publisher token and is scoped`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        client.publish(TestSupport.packaged(), TestSupport.PUBLISHER_TOKEN)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/artifacts").status)

        val list = client.get("/api/v1/artifacts") { header(HttpHeaders.Authorization, "Bearer ${TestSupport.PUBLISHER_TOKEN}") }
        assertEquals(HttpStatusCode.OK, list.status)
        val body = list.body<ArtifactListDto>()
        assertEquals(1, body.artifacts.size)
        assertEquals(listOf("dev.modstreaming.backend.e2e.HelloService"), body.artifacts[0].services)

        val other = client.get("/api/v1/artifacts") { header(HttpHeaders.Authorization, "Bearer ${TestSupport.OTHER_PUBLISHER_TOKEN}") }
        assertEquals(0, other.body<ArtifactListDto>().artifacts.size)
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }

    @Test
    fun `malformed requests are bad requests not crashes`() = testApplication {
        application { modStreamingBackend(TestSupport.deps()) }
        val client = jsonClient()
        val garbage = client.post("/api/v1/verify") { contentType(ContentType.Application.Json); setBody("{\"projectId\": 5}") }
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
        val badCoords = client.post("/api/v1/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequestDto(projectId = "../etc", artifact = ArtifactDto("1", "1.21.1")))
        }
        assertEquals(HttpStatusCode.BadRequest, badCoords.status)
        val badKey = client.post("/api/v1/publish") {
            header(HttpHeaders.Authorization, "Bearer ${TestSupport.PUBLISHER_TOKEN}")
            contentType(ContentType.Application.Json)
            setBody(PublishRequestDto(artifact = "AAAA", key = "AAAA"))
        }
        assertEquals(HttpStatusCode.BadRequest, badKey.status)
    }
}
