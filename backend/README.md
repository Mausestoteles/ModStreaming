# ModStreaming reference backend

Kotlin + Ktor (CIO). Demonstrates artifact upload, storage, verification, download, publisher
authentication and an example license check. The user authorization is intentionally pluggable.

```bash
cp backend.example.json ../backend.json        # edit: public key, publisher tokens, licenses
MODSTREAMING_BACKEND_CONFIG=../backend.json ../gradlew :backend:run
```

Environment variables: `MODSTREAMING_BACKEND_CONFIG`, `MODSTREAMING_BACKEND_PORT`,
`MODSTREAMING_BACKEND_STORAGE`, `MODSTREAMING_AUTH_PROVIDER` (`allow-all`, `deny-all`,
`license-key`), `MODSTREAMING_PUBLISHER_TOKEN` (a token valid for every project).

## Plugging in your own authorization

```kotlin
import dev.modstreaming.backend.*
import dev.modstreaming.backend.auth.*
import dev.modstreaming.backend.config.BackendConfig
import dev.modstreaming.backend.publisher.StaticTokenPublisherAuthenticator
import dev.modstreaming.backend.storage.FileSystemArtifactStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Path

class MyAccountProvider(private val accounts: AccountService) : AuthorizationProvider {
    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult {
        val session = request.credentials["sessionToken"] ?: return AuthorizationResult.Deny("login required")
        val user = accounts.findBySession(session) ?: return AuthorizationResult.Deny("session expired")
        if (!user.owns(request.projectId)) return AuthorizationResult.Deny("no license for ${request.projectId}")
        return AuthorizationResult.Allow(subject = user.id, attributes = mapOf("tier" to user.tier))
    }
}

fun main() {
    val config = BackendConfig.load()
    val deps = BackendDependencies(
        config = config,
        store = FileSystemArtifactStore(Path.of(config.storageDir)),
        authorization = CompositeAuthorizationProvider.allOf(MyAccountProvider(accounts), DeviceLimitProvider()),
        publishers = StaticTokenPublisherAuthenticator(config.publisherTokens()),
    )
    embeddedServer(CIO, port = config.port, host = config.host) { modStreamingBackend(deps) }.start(wait = true)
}
```

`AuthorizationRequest` gives you the project id, artifact coordinates, the generic `credentials`
map, non-sensitive client info, the remote address and request headers. Streaming, encryption and
artifact management stay untouched.

## Endpoints

See [docs/PROTOCOL.md](../docs/PROTOCOL.md).
