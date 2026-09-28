# Security notes

## Goals

* The public jar contains no valuable implementation logic.
* Streamed logic is only obtainable after the operator's backend said ALLOW.
* Whatever the client receives is checked (signature and metadata) **before** decryption and loading.
* No server secrets, signing keys or artifact keys in the client; nothing decrypted touches disk.
* A failing or refusing backend never crashes the game.

## Cryptography

| Purpose | Primitive |
| --- | --- |
| Artifact confidentiality | AES-256-GCM, 96-bit random nonce, 128-bit tag, AAD = artifact identity |
| Artifact authenticity | Ed25519 signature over the manifest bytes; the manifest carries SHA-256 of ciphertext and plaintext |
| Randomness | `java.security.SecureRandom` for keys, nonces and download tokens |
| Fingerprints / integrity | SHA-256 (public key fingerprint, payload and content hashes), constant-time comparisons |

Keys:

* **Ed25519 private key** – developer/CI only (`generateStreamedKeyPair`, env var or file). Never
  uploaded anywhere; the backend only knows the public key.
* **Ed25519 public key** – embedded into the public jar and configured on the backend.
* **AES artifact key** – generated per `packageStreamed` run, uploaded to the backend together
  with the encrypted container, stored separately from the container, and handed only to clients
  the `AuthorizationProvider` allowed. The client keeps it in memory and wipes it after decryption.
* **Download token** – 256 random bits, short TTL, limited uses, bound to one artifact.
* **Publisher token** – bearer token for uploads; compared in constant time.

## What the client verifies before loading

1. Ed25519 signature over `manifest.json` with the embedded public key.
2. Public key fingerprint in the manifest equals the embedded key.
3. Format version and algorithm parameters.
4. `projectId`, `modVersion`, `minecraftVersion`, `loader` equal the requested coordinates
   (prevents serving an artifact of another project/version under a valid signature).
5. SHA-256 of the encrypted payload equals the signed value.
6. Optional operator `ArtifactValidator` rules.
7. After decryption: GCM tag, plaintext size and SHA-256.

Tampering with any byte of the manifest, signature or payload, using a foreign signing key, or a
wrong artifact key results in `LoadStatus.INVALID_ARTIFACT`; nothing is loaded.

## Threat model and limits

* **Unauthorized players** never receive the artifact key; the container alone is useless.
* **Compromised backend storage** reveals only encrypted containers and, if the key files are also
  taken, the artifacts – but it cannot forge new artifacts (no signing key) that clients accept.
* **Malicious backend / MITM without TLS**: cannot make the client load foreign code because every
  artifact must carry a valid signature from the developer's key. Use https in production; plain
  http is only accepted for loopback or with `allowInsecureEndpoint` (development).
* **Authorized players** necessarily get the plaintext code in memory. Anyone who is allowed to run
  the code can, with effort, dump it. ModStreaming raises the bar (no code in the distributed jar,
  per-request authorization, memory-only loading) but is not DRM against a determined authorized
  user. Combine it with server-side validation of anything that matters.
* **Replay of an old ALLOW**: download tokens expire and are limited-use; the artifact key of a
  re-packaged artifact changes with every `packageStreamed`.
* **Reference leaks**: the plugin fails the build if public classes reference streamed classes,
  so no `NoClassDefFoundError`s reveal missing classes at runtime.

## Operational advice

* Never commit `*.private.key`, `*.msa.key` or `backend-data/` (already git-ignored).
* Rotate the publisher token if it leaks; re-publish artifacts after rotating the signing key and
  ship a mod update with the new public key.
* Log only project ids, coordinates, decisions and subjects – never credentials. The reference
  backend and client follow this rule.
