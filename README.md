# ModStreaming

**Minecraft client mod streaming framework.** Keep hooks and mixins in the public mod jar, move the
valuable implementation into a signed and encrypted artifact that is streamed from *your* backend
after *your* authorization logic said yes.

```
@Streamed → build split → upload → client request → operator-defined auth → ALLOW
          → download → signature check → decryption → in-memory class loader → Bridge → hook
```

The framework provides streaming and technical verification. **Who gets access, and under which
rules, is decided entirely by the backend operator** through a single `AuthorizationProvider`.

## Modules

| Module | Purpose |
| --- | --- |
| [`api`](api) | Loader-independent: `@Streamed`, `Bridge`, `BridgeProvider`, auth/verification contracts. No dependencies. |
| [`core`](core) | `StreamConfig`, `java.net.http` client, AES-256-GCM, Ed25519, artifact container, in-memory class loader, service registration, `StreamingRuntime`. No Fabric dependency, no server secrets. |
| [`fabric`](fabric) | Fabric client bootstrap (`modstreaming` mod). Discovers mods with an embedded stream config and loads their artifacts. Never crashes the game. |
| [`gradle-plugin`](gradle-plugin) | `dev.modstreaming` plugin: `splitStreamed`, `packageStreamed`, `publishStreamed`, `generateStreamedKeyPair`. ASM based, Loom aware. |
| [`backend`](backend) | Kotlin + Ktor reference backend: upload, storage, verification, download, publisher auth, example license check, pluggable `AuthorizationProvider`. |
| [`example-mod`](example-mod) | Stonecutter/Loom mod (1.21.1, 1.21.4) with a public interface, a hook and a `@Streamed` implementation. |

Requirements: Java 21, Gradle 8.14.3 (wrapper included), Maven Central and `maven.fabricmc.net`.

## Hook-shim architecture

Public jar (shipped to everyone):

```java
public interface GreetingService { String greet(String player); }

// Mixin/hook: no reference to the implementation, no-op until the service is loaded
Bridge.get(GreetingService.class).ifPresent(s -> LOGGER.info(s.greet(name)));
```

Streamed jar (never shipped, only streamed to authorized clients):

```java
@Streamed
public final class SecretGreetingService implements GreetingService {
    public String greet(String player) { /* the valuable logic */ }
}
```

The Gradle plugin removes every `@Streamed` class (including nested, local and anonymous classes,
or whole packages via `package-info.java`) from the public jar, writes
`META-INF/modstreaming/stream-config.json` into it and produces the streamed jar with generated
service metadata. A direct reference from public code to a streamed class fails the build
(`strict`), because it would crash at runtime.

## Quick start

1. **Build and publish the framework locally**

   ```bash
   ./gradlew build publishToMavenLocal
   ```

2. **Apply the plugin to your mod** (see [`example-mod`](example-mod) for a Loom/Stonecutter setup)

   ```kotlin
   plugins {
       id("fabric-loom") version "1.13.6"
       id("dev.modstreaming") version "0.1.0"
   }

   dependencies {
       modImplementation("dev.modstreaming:modstreaming-fabric:0.1.0")
       include("dev.modstreaming:modstreaming-fabric:0.1.0")
   }

   modStreaming {
       endpoint.set("https://stream.example.com")
       projectId.set("example-mod")
       publicKey.set("<base64 Ed25519 public key>")
       minecraftVersion.set("1.21.1")      // modVersion defaults to project.version, loader to "fabric"
   }
   ```

3. **Generate a key pair** once: `./gradlew generateStreamedKeyPair` prints the public key for
   `modStreaming.publicKey` and writes the private key to `modstreaming-keys/` (git-ignored).

4. **Build**: `./gradlew build` produces the cleaned public jar in `build/libs/` and the streamed jar
   in `build/modstreaming/`. Nothing is uploaded.

5. **Package and publish** (explicit, never part of `build`):

   ```bash
   export MODSTREAMING_SIGNING_KEY_FILE=modstreaming-keys/modstreaming.private.key
   export MODSTREAMING_PUBLISH_TOKEN=<publisher token configured on the backend>
   ./gradlew publishStreamed
   ```

6. **Run the backend** (reference implementation):

   ```bash
   cp backend/backend.example.json backend.json   # fill in publicKey + publisherTokens
   MODSTREAMING_BACKEND_CONFIG=backend.json ./gradlew :backend:run
   ```

7. **Players** put their credentials in `config/modstreaming/<projectId>.json`
   (or the mod supplies them through a `modstreaming` entrypoint):

   ```json
   { "credentials": { "licenseKey": "EXAMPLE-LICENSE-KEY-1" } }
   ```

## Gradle tasks

| Task | Description | Part of `build`? |
| --- | --- | --- |
| `splitStreamed` | Detects `@Streamed` via ASM, moves classes (+ nested) and configured resources to the streamed jar, cleans the public jar, embeds the stream config, writes `services.json` and `split-report.json`. | yes |
| `packageStreamed` | Signs (Ed25519) and encrypts (AES-256-GCM, random key) the streamed jar into `<projectId>-<modVersion>-<mc>-<loader>.msa` plus `.msa.key`. | no |
| `publishStreamed` | Uploads container + key to `POST {endpoint}/api/v1/publish` with the publisher token. | **never** |
| `generateStreamedKeyPair` | Creates an Ed25519 key pair (`--force` to overwrite). | no |

With Fabric Loom applied, the split happens on the dev jar before remapping: `remapJar` remaps the
public jar as usual and an extra `remapStreamedJar` task remaps the streamed jar so streamed code
may use Minecraft classes. Mixins must stay in the public jar.

Extension properties: `endpoint`, `projectId`, `publicKey`, `modVersion`, `minecraftVersion`,
`loader`, `loadMode` (`ASYNC`/`BLOCKING`), `strict`, `allowInsecureEndpoint`, `streamedResources`
(Ant patterns of resources to move), `inputJar`, `outputDirectory`, `signingKey`, `signingKeyFile`,
`publishToken`. Secrets default to `MODSTREAMING_SIGNING_KEY`, `MODSTREAMING_SIGNING_KEY_FILE` and
`MODSTREAMING_PUBLISH_TOKEN`.

## Open authorization architecture

The framework does not prescribe an authentication method. The client sends a generic request:

```json
{
  "protocolVersion": 1,
  "projectId": "example-mod",
  "artifact": { "modVersion": "1.0.0", "minecraftVersion": "1.21.1", "loader": "fabric" },
  "credentials": { "token": "...", "customField": "..." },
  "client": { "frameworkVersion": "0.1.0", "javaVersion": "21", "os": "linux", "loader": "fabric" }
}
```

and the backend answers `ALLOW` (with a download token and the artifact key) or `DENY`. The meaning
of `credentials` is defined by the operator's implementation of

```kotlin
interface AuthorizationProvider {
    suspend fun authorize(request: AuthorizationRequest): AuthorizationResult
}
```

Own accounts, license keys, Minecraft UUIDs, Discord links, website sessions, device ids, a custom
database, an external auth service, or any combination (`CompositeAuthorizationProvider`) can be
used without changing streaming, encryption or artifact management. The reference backend ships
`allow-all`, `deny-all` and an example `license-key` provider. Credentials are never logged.

See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the full wire contract,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the runtime pipeline and
[docs/SECURITY.md](docs/SECURITY.md) for the threat model and cryptography.

## Runtime behaviour on the client

* `ASYNC` (default): the game keeps starting; hooks are no-ops until the artifact is loaded, and
  `Bridge.whenAvailable(...)` notifies interested code.
* `BLOCKING`: mod initialization waits up to `blockingTimeoutMillis`.
* DENY, an unreachable backend, a tampered or foreign artifact, a wrong key: logged, hooks stay
  no-ops, **Minecraft never crashes**. `ModStreamingFabric.bootstrap().reload(projectId)` retries
  later (for example after an in-game login).

## Tests

`./gradlew build` runs everything: Bridge, in-memory class loader, AES-GCM, Ed25519, artifact
round trip with manipulated payload/manifest/key, config validation, HTTP client against a stub
backend, the full client pipeline, ASM splitting (nested/anonymous/local classes, package-level
annotation, leak detection), a Gradle TestKit build of a synthetic mod (build split, package,
publish, strict mode, key generation), backend upload/download, ALLOW/DENY, custom
`AuthorizationProvider`, tampered uploads and an end-to-end test that streams a `@Streamed` class
from the Ktor backend into the Bridge.

The example mod is a separate Loom build:

```bash
cd example-mod && ./gradlew :1.21.1:build   # or :1.21.4:build
```
