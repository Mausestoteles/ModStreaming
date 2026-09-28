package dev.modstreaming.backend

import dev.modstreaming.backend.api.VerifyResponseDto
import dev.modstreaming.backend.auth.AllowAllAuthorizationProvider
import dev.modstreaming.backend.auth.AuthorizationProvider
import dev.modstreaming.backend.auth.AuthorizationRequest
import dev.modstreaming.backend.auth.AuthorizationResult
import dev.modstreaming.backend.auth.CompositeAuthorizationProvider
import dev.modstreaming.backend.auth.DenyAllAuthorizationProvider
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shows that an operator can plug in arbitrary logic (here: a Discord id + Minecraft UUID pairing
 * with an "account database") without touching anything else.
 */
class CustomAuthorizationProviderTest {

    private class AccountDatabaseProvider : AuthorizationProvider {
        val seen = CopyOnWriteArrayList<AuthorizationRequest>()
        private val accounts = mapOf("discord:1234" to "069a79f4-44e9-4726-a5be-fca90e38aaf5")

        override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
            seen.add(request)
            val discord = request.credentials["discordId"] ?: return AuthorizationResult.Deny("link your Discord account first")
            val uuid = request.credentials["minecraftUuid"] ?: return AuthorizationResult.Deny("minecraft uuid missing")
            val linked = accounts["discord:$discord"] ?: return AuthorizationResult.Deny("unknown Discord account")
            if (linked != uuid) return AuthorizationResult.Deny("this Minecraft account is not linked to that Discord user")
            return AuthorizationResult.Allow(subject = "discord:$discord", attributes = mapOf("tier" to "gold", "message" to "welcome back"))
        }
    }

    @Test
    fun `custom provider receives the generic credential structure and drives the decision`() = testApplication {
        val provider = AccountDatabaseProvider()
        application { modStreamingBackend(TestSupport.deps(authorization = provider)) }
        val client = jsonClient()
        client.publish(TestSupport.packaged(), TestSupport.PUBLISHER_TOKEN)

        val denied = client.verify(mapOf("discordId" to "9999", "minecraftUuid" to "x"))
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals("unknown Discord account", denied.body<VerifyResponseDto>().reason)

        val allowed = client.verify(mapOf("discordId" to "1234", "minecraftUuid" to "069a79f4-44e9-4726-a5be-fca90e38aaf5", "extra" to "1"))
        assertEquals(HttpStatusCode.OK, allowed.status)
        val grant = allowed.body<VerifyResponseDto>()
        assertEquals("ALLOW", grant.decision)
        assertEquals("gold", grant.attributes["tier"])
        assertEquals("welcome back", grant.attributes["message"])
        assertEquals("discord:1234", grant.attributes["subject"])
        assertNotNull(grant.downloadToken)

        val request = provider.seen.last()
        assertEquals("example-mod", request.projectId)
        assertEquals("1.21.1", request.artifact.minecraftVersion())
        assertEquals("1", request.credentials["extra"])
        assertNotNull(request.remoteAddress)
        assertTrue(request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
    }

    @Test
    fun `provider exceptions become 500 without leaking`() = testApplication {
        application {
            modStreamingBackend(TestSupport.deps(authorization = AuthorizationProvider { throw IllegalStateException("db down: secret=xyz") }))
        }
        val client = jsonClient()
        val response = client.verify(mapOf("licenseKey" to "abc"))
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(!response.bodyAsText().contains("secret=xyz"))
    }

    @Test
    fun `composite providers combine decisions`() = runTest {
        val request = AuthorizationRequest("p", TestSupport.coordinates, emptyMap(), emptyMap(), null, emptyMap())
        val subjectProvider = AuthorizationProvider { AuthorizationResult.Allow("user-1", mapOf("a" to "1")) }
        val all = CompositeAuthorizationProvider.allOf(AllowAllAuthorizationProvider, subjectProvider)
        val allResult = all.authorize(request)
        assertIs<AuthorizationResult.Allow>(allResult)
        assertEquals("anonymous+user-1", allResult.subject)
        assertEquals(mapOf("a" to "1"), allResult.attributes)
        assertIs<AuthorizationResult.Deny>(CompositeAuthorizationProvider.allOf(AllowAllAuthorizationProvider, DenyAllAuthorizationProvider).authorize(request))
        assertIs<AuthorizationResult.Allow>(CompositeAuthorizationProvider.anyOf(DenyAllAuthorizationProvider, subjectProvider).authorize(request))
        assertIs<AuthorizationResult.Deny>(CompositeAuthorizationProvider.anyOf(DenyAllAuthorizationProvider).authorize(request))
    }
}
