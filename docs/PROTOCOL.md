# Wire protocol (version 1)

All endpoints are relative to the configured `endpoint` (https; plain http only for loopback or
`allowInsecureEndpoint`). Bodies are JSON unless stated otherwise. Errors use
`{"error": "<message>"}`.

## Client → backend

### `POST /api/v1/verify`

```json
{
  "protocolVersion": 1,
  "projectId": "example-mod",
  "artifact": { "modVersion": "1.0.0", "minecraftVersion": "1.21.1", "loader": "fabric" },
  "credentials": { "licenseKey": "...", "minecraftUuid": "...", "customField": "..." },
  "client": { "frameworkVersion": "0.1.0", "javaVersion": "21", "os": "linux", "loader": "fabric" }
}
```

* `credentials` is an arbitrary string map; its meaning is defined by the operator's
  `AuthorizationProvider`. It is transmitted over TLS and never logged by either side.
* `client` carries only coarse, non-sensitive environment information.

Responses:

| Status | Body | Meaning |
| --- | --- | --- |
| `200` | `{"decision":"ALLOW","downloadToken":"…","key":"<base64 32 bytes>","artifactId":"…","expiresAt":"<ISO-8601>","attributes":{…}}` | authorized; token is short-lived and limited-use |
| `403` | `{"decision":"DENY","reason":"…","attributes":{…}}` | refused by the operator's logic |
| `404` | `{"decision":"DENY","reason":"no artifact available for …"}` | authorized, but nothing published for these coordinates |
| `400` | `{"error":"…"}` | malformed request |
| `5xx` | | backend error; the client treats it as retryable `UNAVAILABLE` |

`attributes` is a free string map for the operator (for example `subject`, a display message or a
tier); the client exposes it in `LoadResult.attributes()`.

### `GET /api/v1/artifacts/download`

Header `Authorization: Bearer <downloadToken>`. Returns the container as
`application/octet-stream` (`200`) plus `X-ModStreaming-Artifact-Id`. `401` for unknown, expired or
exhausted tokens, `404` if the artifact vanished.

## Publisher → backend

### `POST /api/v1/publish`

Header `Authorization: Bearer <publisherToken>`.

```json
{ "protocolVersion": 1, "artifact": "<base64 .msa container>", "key": "<base64 32 byte AES key>" }
```

The backend parses the container, checks the Ed25519 signature against the project's configured
public key, verifies the payload hash and that the key decrypts the payload, then stores it.
`201` with

```json
{ "artifactId": "…", "projectId": "…", "modVersion": "…", "minecraftVersion": "…", "loader": "…", "replaced": false }
```

`401` invalid token, `403` token not allowed for the project / project not registered,
`400` invalid or tampered artifact.

### `GET /api/v1/artifacts?projectId=…`

Publisher-authenticated listing of stored artifacts.

## Artifact container (`.msa`)

A ZIP with three entries:

| Entry | Content |
| --- | --- |
| `manifest.json` | signed metadata (raw bytes are what is signed) |
| `manifest.sig` | 64 byte Ed25519 signature over `manifest.json` |
| `payload.bin` | `nonce (12) ‖ AES-256-GCM ciphertext ‖ tag (16)` of the streamed jar |

Manifest fields: `formatVersion`, `artifactId` (UUID), `projectId`, `modVersion`,
`minecraftVersion`, `loader`, `createdAt`, `encryption` (`AES/GCM/NoPadding`, 256/12/128),
`signature` (`Ed25519`, `publicKeyFingerprint` = SHA-256 of the raw public key), `payloadSha256`,
`contentSha256`, `contentSize`, `services` (`[{service, implementation}]`), `frameworkVersion`.

AES-GCM additional authenticated data is `modstreaming/v1|<artifactId>|<projectId>:<modVersion>:<minecraftVersion>:<loader>`.

## Embedded stream config

`META-INF/modstreaming/stream-config.json` in the public jar (written by `splitStreamed`):

```json
{
  "configVersion": 1,
  "endpoint": "https://stream.example.com",
  "projectId": "example-mod",
  "publicKey": "<base64 raw Ed25519 public key>",
  "modVersion": "1.0.0",
  "minecraftVersion": "1.21.1",
  "loader": "fabric",
  "services": ["com.example.streamdemo.api.GreetingService"],
  "loadMode": "ASYNC",
  "connectTimeoutMillis": 10000,
  "requestTimeoutMillis": 30000,
  "blockingTimeoutMillis": 20000,
  "allowInsecureEndpoint": false,
  "maxArtifactBytes": 67108864
}
```

Only public information is embedded; there are no server secrets in the client.
