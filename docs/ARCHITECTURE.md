# Architecture

## Components

```
┌────────────────────────── developer machine / CI ──────────────────────────┐
│ mod sources                                                                │
│   public classes (hooks, mixins, interfaces)   @Streamed implementations   │
│                 │ jar                                   │                  │
│                 ▼                                       ▼                  │
│        splitStreamed (ASM) ── public jar + stream-config ── streamed jar    │
│                                     │                           │          │
│                             remapJar (Loom)            remapStreamedJar    │
│                                     │                           │          │
│                              build/libs/*.jar         packageStreamed      │
│                                                (sign Ed25519, encrypt AES) │
│                                                   .msa + .msa.key          │
│                                                           │ publishStreamed│
└───────────────────────────────────────────────────────────┼────────────────┘
                                                            ▼
┌────────────────────────────── backend (Ktor) ──────────────────────────────┐
│ POST /api/v1/publish  ── publisher auth ── signature check ── ArtifactStore │
│ POST /api/v1/verify   ── AuthorizationProvider (operator logic) ── token   │
│ GET  /api/v1/artifacts/download ── token ── container bytes                │
└────────────────────────────────────────────────────────────────────────────┘
                                                            ▲
┌──────────────────────────────── client ────────────────────┼───────────────┐
│ Fabric bootstrap discovers stream-config in the public jar │               │
│ StreamingRuntime: verify → download → ArtifactVerifier → decrypt           │
│   → InMemoryClassLoader → ServiceRegistrar → Bridge                        │
│ hooks: Bridge.get(Service.class).ifPresent(...)                            │
└────────────────────────────────────────────────────────────────────────────┘
```

## Client pipeline (`core`)

`StreamingRuntime.load()` runs these steps and returns a `LoadResult` instead of throwing:

1. **Credentials** – the `CredentialsProvider` (entrypoint or `config/modstreaming/<projectId>.json`)
   supplies a `Map<String, String>`; it is sent as-is and never logged.
2. **Verification request** – `HttpVerificationService` posts the JSON contract to
   `{endpoint}/api/v1/verify`. `DENY` → `LoadStatus.DENIED`; transport/5xx → `UNAVAILABLE`.
3. **Download** – `HttpArtifactDownloader` fetches the container with the bearer download token,
   bounded by `maxArtifactBytes`.
4. **Signature + metadata** – `ArtifactVerifier` checks the Ed25519 signature over the raw manifest
   bytes with the public key embedded in the mod, the key fingerprint, the format/algorithms, that
   the manifest coordinates equal the requested ones and that the SHA-256 of the encrypted payload
   matches the signed manifest. Only then does anything get decrypted.
5. **Decryption** – AES-256-GCM with the key from the verification response; AAD binds the payload
   to artifact id and coordinates; the plaintext hash and size are checked against the manifest.
   The key is wiped afterwards.
6. **In-memory class loader** – `InMemoryClassLoader` owns exactly the streamed classes (self-first)
   and delegates everything else to the public mod's loader, so service interfaces keep their
   identity. Nothing is written to disk.
7. **Registration** – `ServiceRegistrar` instantiates each binding from the signed manifest
   (`INSTANCE` field, `create()` factory or no-arg constructor), checks that the interface comes
   from the public side and that the implementation really implements it, and registers it in the
   `Bridge`. `Bridge.whenAvailable` callbacks fire.

## Bridge

`Bridge` is a static facade over a `BridgeProvider` (default: concurrent in-memory map). Hooks use
`Bridge.get(Service.class)`, `isAvailable`, `whenAvailable`. The runtime registers and unregisters
bindings; `setProvider` allows loaders or tests to swap the registry.

## Fabric bootstrap (`fabric`)

`ModStreamingFabric` (client entrypoint of mod `modstreaming`) iterates all loaded mods, reads
`META-INF/modstreaming/stream-config.json` where present, collects the mod's optional
`modstreaming` entrypoints (`ModStreamingEntrypoint`: credentials + result callback) and starts a
`StreamingRuntime` per project on the `ModStreaming-Loader` daemon thread (`ASYNC`) or waits with a
timeout (`BLOCKING`). Every failure is logged; nothing propagates into Minecraft.

Mods embed the runtime via jar-in-jar (`include("dev.modstreaming:modstreaming-fabric:...")`).
Fabric deduplicates nested copies by mod id.

## Gradle plugin (`gradle-plugin`)

* `StreamedScanner` reads each class with ASM: `@Streamed` (class or `package-info`), directly
  implemented interfaces, `InnerClasses`/`EnclosingMethod` (nested, local, anonymous classes).
* `SplitPlan` computes the streamed set (annotated, annotated package, enclosed in a streamed
  class, or `Outer$...` prefix as fallback) and the service bindings (`@Streamed(value)` or all
  non-JDK interfaces; `register = false` moves without registering).
* `ReferenceChecker` scans the remaining public classes for references to streamed classes and
  fails the build in `strict` mode.
* `JarSplitter` writes both jars, embeds the stream config and `services.json`, moves resources
  matching `streamedResources`.
* Loom: `remapJar.inputFile` is redirected to the public jar; `remapStreamedJar` (a Loom
  `RemapJarTask` created reflectively, so no compile dependency on a Loom version) remaps the
  streamed jar. Plain Java builds: the unsplit `jar` gets the `unsplit` classifier and the public
  jar takes the canonical path.

## Backend (`backend`)

`BackendDependencies` bundles `ArtifactStore`, `AuthorizationProvider`, `PublisherAuthenticator`,
`DownloadTokenService` and the config; `Application.modStreamingBackend(deps)` installs the routes.
Operators compose their own dependencies (custom provider, store, publisher auth) in a `main`
of their own and keep the rest.

Artifacts are stored as uploaded (encrypted) plus the key in a separate file; storage compromise
alone reveals nothing without the key, and the key is only ever sent after an ALLOW decision.
