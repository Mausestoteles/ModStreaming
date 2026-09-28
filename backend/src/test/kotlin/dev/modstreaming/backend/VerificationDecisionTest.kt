package dev.modstreaming.backend

import dev.modstreaming.backend.api.VerifyResponseDto
import dev.modstreaming.backend.auth.LicenseKeyAuthorizationProvider
import dev.modstreaming.backend.config.AuthorizationConfig
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationDecisionTest {

    private val provider = LicenseKeyAuthorizationProvider(mapOf("example-mod" to listOf("KEY-ONE", "KEY-TWO")))

    @Test
    fun `license key provider allows and denies`() = testApplication {
        application { modStreamingBackend(TestSupport.deps(authorization = provider)) }
        val client = jsonClient()
        client.publish(TestSupport.packaged(), TestSupport.PUBLISHER_TOKEN)

        val noCredentials = client.verify()
        assertEquals(HttpStatusCode.Forbidden, noCredentials.status)
        val denied = noCredentials.body<VerifyResponseDto>()
        assertEquals("DENY", denied.decision)
        assertTrue(denied.reason!!.contains("licenseKey"))
        assertNull(denied.downloadToken)
        assertNull(denied.key)

        val wrongKey = client.verify(mapOf("licenseKey" to "KEY-NOPE"))
        assertEquals(HttpStatusCode.Forbidden, wrongKey.status)
        assertEquals("DENY", wrongKey.body<VerifyResponseDto>().decision)

        val otherProjectKey = client.verify(mapOf("licenseKey" to "KEY-ONE"), projectId = "other-mod")
        assertEquals(HttpStatusCode.Forbidden, otherProjectKey.status)

        val allowed = client.verify(mapOf("licenseKey" to "KEY-TWO", "customField" to "ignored"))
        assertEquals(HttpStatusCode.OK, allowed.status)
        val grant = allowed.body<VerifyResponseDto>()
        assertEquals("ALLOW", grant.decision)
        assertTrue(grant.attributes["subject"]!!.startsWith("license:"))
        assertTrue(!grant.attributes["subject"]!!.contains("KEY-TWO"), "subject must not echo the secret")
        assertEquals(HttpStatusCode.OK, client.download(grant.downloadToken).status)
    }

    @Test
    fun `provider is selected from configuration`() {
        val allow = BackendDependencies.fromConfig(TestSupport.config(authorization = AuthorizationConfig(provider = "allow-all")), emptyMap())
        val deny = BackendDependencies.fromConfig(TestSupport.config(authorization = AuthorizationConfig(provider = "deny-all")), emptyMap())
        val license = BackendDependencies.fromConfig(
            TestSupport.config(authorization = AuthorizationConfig(provider = "license-key", licenses = mapOf("example-mod" to listOf("K")))),
            emptyMap(),
        )
        assertEquals("AllowAllAuthorizationProvider", allow.authorization::class.simpleName)
        assertEquals("DenyAllAuthorizationProvider", deny.authorization::class.simpleName)
        assertEquals("LicenseKeyAuthorizationProvider", license.authorization::class.simpleName)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            BackendDependencies.fromConfig(TestSupport.config(authorization = AuthorizationConfig(provider = "magic")), emptyMap())
        }
    }

    @Test
    fun `deny-all never issues tokens`() = testApplication {
        application { modStreamingBackend(TestSupport.deps(authorization = dev.modstreaming.backend.auth.DenyAllAuthorizationProvider)) }
        val client = jsonClient()
        client.publish(TestSupport.packaged(), TestSupport.PUBLISHER_TOKEN)
        val response = client.verify(mapOf("licenseKey" to "anything"))
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.body<VerifyResponseDto>().downloadToken)
    }
}
