package dev.modstreaming.core.artifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The on-the-wire artifact container: a ZIP file with exactly three entries.
 *
 * <pre>
 * manifest.json  - signed metadata (exact bytes are signed)
 * manifest.sig   - 64 byte Ed25519 signature over manifest.json
 * payload.bin    - AES-GCM: nonce || ciphertext || tag, AAD = manifest.json
 * </pre>
 *
 * @param manifestBytes the raw manifest bytes exactly as stored
 * @param signature     the detached signature
 * @param payload       the encrypted streamed jar
 */
public record ArtifactContainer(byte[] manifestBytes, byte[] signature, byte[] payload) {

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String SIGNATURE_ENTRY = "manifest.sig";
    public static final String PAYLOAD_ENTRY = "payload.bin";
    public static final String FILE_EXTENSION = ".msa";

    private static final long MAX_MANIFEST_BYTES = 1024 * 1024;

    public ArtifactContainer {
        Objects.requireNonNull(manifestBytes, "manifestBytes");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(payload, "payload");
    }

    /** Parses the manifest bytes. Does <em>not</em> verify the signature. */
    public ArtifactManifest parseManifest() throws ArtifactException {
        return ArtifactManifest.fromJsonBytes(manifestBytes);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length + manifestBytes.length + 512);
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            write(zip, MANIFEST_ENTRY, manifestBytes);
            write(zip, SIGNATURE_ENTRY, signature);
            write(zip, PAYLOAD_ENTRY, payload);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write artifact container", e);
        }
        return bos.toByteArray();
    }

    /**
     * Reads a container, enforcing {@code maxPayloadBytes} to guard against decompression bombs.
     */
    public static ArtifactContainer fromBytes(byte[] bytes, long maxPayloadBytes) throws ArtifactException {
        Objects.requireNonNull(bytes, "bytes");
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                long limit = switch (name) {
                    case MANIFEST_ENTRY -> MAX_MANIFEST_BYTES;
                    case SIGNATURE_ENTRY -> 1024;
                    case PAYLOAD_ENTRY -> maxPayloadBytes;
                    default -> throw new ArtifactException("unexpected entry in artifact container: " + name);
                };
                if (entries.containsKey(name)) {
                    throw new ArtifactException("duplicate entry in artifact container: " + name);
                }
                entries.put(name, readLimited(zip, limit, name));
            }
        } catch (IOException e) {
            throw new ArtifactException("artifact container is not a valid ZIP file");
        }
        byte[] manifest = entries.get(MANIFEST_ENTRY);
        byte[] signature = entries.get(SIGNATURE_ENTRY);
        byte[] payload = entries.get(PAYLOAD_ENTRY);
        if (manifest == null || signature == null || payload == null) {
            throw new ArtifactException("artifact container is incomplete (needs manifest.json, manifest.sig, payload.bin)");
        }
        return new ArtifactContainer(manifest, signature, payload);
    }

    public static ArtifactContainer fromBytes(byte[] bytes) throws ArtifactException {
        return fromBytes(bytes, 64L * 1024 * 1024);
    }

    private static void write(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readLimited(ZipInputStream in, long limit, String name) throws IOException, ArtifactException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new ArtifactException("artifact entry '" + name + "' exceeds the size limit of " + limit + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
