package dev.modstreaming.core.runtime;

import dev.modstreaming.api.Bridge;
import dev.modstreaming.api.BridgeProvider;
import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.api.auth.ArtifactDownloader;
import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.api.auth.VerificationRequest;
import dev.modstreaming.api.auth.VerificationResult;
import dev.modstreaming.api.auth.VerificationService;
import dev.modstreaming.api.verify.ArtifactValidator;
import dev.modstreaming.api.verify.SignatureVerifier;
import dev.modstreaming.core.artifact.ArtifactContainer;
import dev.modstreaming.core.artifact.ArtifactException;
import dev.modstreaming.core.artifact.ArtifactVerifier;
import dev.modstreaming.core.artifact.VerifiedArtifact;
import dev.modstreaming.core.client.ClientInfo;
import dev.modstreaming.core.client.HttpArtifactDownloader;
import dev.modstreaming.core.client.HttpVerificationService;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.crypto.Ed25519SignatureVerifier;
import dev.modstreaming.core.loader.InMemoryClassLoader;
import dev.modstreaming.core.loader.JarContents;
import dev.modstreaming.core.log.StreamLogger;
import dev.modstreaming.core.registry.ServiceRegistrar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client-side pipeline:
 *
 * <pre>
 * verification request → ALLOW/DENY → download → signature + metadata check → decryption
 *   → in-memory class loader → service registration in the Bridge
 * </pre>
 *
 * <p>{@link #load()} never throws for expected conditions (denied, offline, tampered artifact);
 * it returns a {@link LoadResult} describing what happened so loader integrations can log it and
 * keep the game running.</p>
 */
public final class StreamingRuntime {

    private final StreamConfig config;
    private final CredentialsProvider credentials;
    private final VerificationService verificationService;
    private final ArtifactDownloader downloader;
    private final SignatureVerifier signatureVerifier;
    private final ArtifactValidator validator;
    private final ClassLoader parentLoader;
    private final BridgeProvider bridge;
    private final StreamLogger log;
    private final StreamingListener listener;
    private final Map<String, String> clientInfo;

    private final AtomicReference<LoadResult> lastResult = new AtomicReference<>();
    private final AtomicReference<List<ServiceRegistrar.Registration>> registrations = new AtomicReference<>(List.of());

    private StreamingRuntime(Builder b) {
        this.config = b.config;
        this.credentials = b.credentials;
        this.verificationService = b.verificationService != null ? b.verificationService : new HttpVerificationService(config);
        this.downloader = b.downloader != null ? b.downloader : new HttpArtifactDownloader(config);
        this.signatureVerifier = b.signatureVerifier != null ? b.signatureVerifier
                : new Ed25519SignatureVerifier(config.decodedPublicKey());
        this.validator = b.validator;
        this.parentLoader = b.parentLoader != null ? b.parentLoader : StreamingRuntime.class.getClassLoader();
        this.bridge = b.bridge != null ? b.bridge : Bridge.provider();
        this.log = (b.log != null ? b.log : StreamLogger.system()).withPrefix(config.projectId());
        this.listener = b.listener != null ? b.listener : StreamingListener.none();
        this.clientInfo = b.clientInfo != null ? b.clientInfo : ClientInfo.collect(config.coordinates().loader());
    }

    public static Builder builder(StreamConfig config) {
        return new Builder(config);
    }

    public StreamConfig config() {
        return config;
    }

    /** The most recent result, if {@link #load()} ran. */
    public Optional<LoadResult> lastResult() {
        return Optional.ofNullable(lastResult.get());
    }

    public CompletableFuture<LoadResult> loadAsync(Executor executor) {
        return CompletableFuture.supplyAsync(this::load, executor);
    }

    /** Runs the whole pipeline synchronously. Never throws. */
    public LoadResult load() {
        String projectId = config.projectId();
        LoadResult computed;
        try {
            computed = doLoad();
        } catch (Throwable t) {
            computed = LoadResult.of(LoadStatus.FAILED, "unexpected error: " + t.getClass().getSimpleName(), t);
            log.error("streaming failed unexpectedly", t);
        }
        final LoadResult result = computed;
        lastResult.set(result);
        safely(() -> {
            if (result.isLoaded()) {
                listener.onLoaded(projectId, result);
            } else if (result.status() != LoadStatus.DENIED) {
                listener.onFailed(projectId, result);
            }
            listener.onFinished(projectId, result);
        });
        return result;
    }

    /** Unregisters every service registered by the last successful load. */
    public int unload() {
        List<ServiceRegistrar.Registration> regs = registrations.getAndSet(List.of());
        return new ServiceRegistrar(bridge, log).unregister(regs);
    }

    private LoadResult doLoad() {
        String projectId = config.projectId();

        // 1. Gather credentials (operator-defined structure; never logged).
        Map<String, String> creds;
        try {
            creds = credentials.credentials();
            if (creds == null) {
                creds = Map.of();
            }
        } catch (RuntimeException e) {
            log.error("credentials provider failed", e);
            return LoadResult.of(LoadStatus.FAILED, "credentials provider failed: " + e.getClass().getSimpleName(), e);
        }
        VerificationRequest request = new VerificationRequest(config.coordinates(), creds, clientInfo);

        // 2. Verification request → backend decides.
        safely(() -> listener.onVerificationStarted(projectId));
        VerificationResult grant;
        try {
            log.info("requesting verification for " + config.coordinates().key() + " at " + config.endpoint().getHost());
            grant = verificationService.verify(request);
        } catch (VerificationException e) {
            log.warn("verification unavailable: " + e.getMessage());
            return LoadResult.of(LoadStatus.UNAVAILABLE, e.getMessage(), e);
        }
        if (!grant.isAllowed()) {
            log.info("backend denied streaming: " + grant.reason());
            safely(() -> listener.onDenied(projectId, grant));
            return new LoadResult(LoadStatus.DENIED, grant.reason(), null, List.of(), grant.attributes(), null, null);
        }
        safely(() -> listener.onVerified(projectId, grant));

        byte[] key = grant.artifactKey();
        try {
            // 3. Download the encrypted container.
            byte[] containerBytes;
            try {
                containerBytes = downloader.download(grant);
            } catch (VerificationException e) {
                log.warn("artifact download failed: " + e.getMessage());
                return LoadResult.of(LoadStatus.UNAVAILABLE, e.getMessage(), e);
            }
            safely(() -> listener.onDownloaded(projectId, containerBytes.length));
            log.debug("downloaded " + containerBytes.length + " bytes");

            // 4. Signature and metadata verification (before decryption).
            VerifiedArtifact verified;
            byte[] jarBytes;
            try {
                ArtifactContainer container = ArtifactContainer.fromBytes(containerBytes, config.maxArtifactBytes());
                ArtifactVerifier verifier = new ArtifactVerifier(signatureVerifier, expectedFingerprint(), validator);
                verified = verifier.verify(container, config.coordinates());
                log.info("artifact " + verified.manifest().artifactId() + " verified (" + signatureVerifier.algorithm() + ")");

                // 5. Decryption and content hash check.
                jarBytes = verified.decrypt(key);
            } catch (ArtifactException e) {
                log.error("artifact rejected: " + e.getMessage());
                return LoadResult.of(LoadStatus.INVALID_ARTIFACT, e.getMessage(), e);
            } finally {
                AesGcm.wipe(key);
                grant.wipe();
            }

            // 6. In-memory class loader.
            JarContents contents;
            try {
                contents = JarContents.parse(jarBytes, config.maxArtifactBytes() * 4);
            } catch (IOException e) {
                return LoadResult.of(LoadStatus.INVALID_ARTIFACT, "streamed jar is malformed: " + e.getMessage(), e);
            } finally {
                java.util.Arrays.fill(jarBytes, (byte) 0);
            }
            InMemoryClassLoader loader = new InMemoryClassLoader(verified.manifest().artifactId(), contents, parentLoader);
            log.debug("loaded " + loader.classNames().size() + " streamed class(es) into memory");

            // 7. Register services in the Bridge.
            List<ServiceBinding> bindings = verified.manifest().services();
            ServiceRegistrar.Result registration = new ServiceRegistrar(bridge, log).register(bindings, loader);
            registrations.set(registration.registered());
            List<String> serviceNames = new ArrayList<>();
            registration.registered().forEach(r -> serviceNames.add(r.service().getName()));
            if (!registration.allRegistered()) {
                StringBuilder sb = new StringBuilder("some services could not be registered:");
                registration.failed().forEach(f -> sb.append(' ').append(f.binding().service()).append(" (").append(f.reason()).append(')'));
                if (registration.registered().isEmpty()) {
                    return new LoadResult(LoadStatus.FAILED, sb.toString(), verified.manifest().artifactId(), serviceNames,
                            grant.attributes(), null, loader);
                }
                log.warn(sb.toString());
                return new LoadResult(LoadStatus.LOADED, "loaded with warnings: " + sb, verified.manifest().artifactId(),
                        serviceNames, grant.attributes(), null, loader);
            }
            String message = "loaded artifact " + verified.manifest().artifactId() + " with " + serviceNames.size() + " service(s)";
            log.info(message);
            return new LoadResult(LoadStatus.LOADED, message, verified.manifest().artifactId(), serviceNames,
                    grant.attributes(), null, loader);
        } finally {
            AesGcm.wipe(key);
            grant.wipe();
        }
    }

    private String expectedFingerprint() {
        if (signatureVerifier instanceof Ed25519SignatureVerifier ed) {
            return ed.fingerprint();
        }
        try {
            return Ed25519.fingerprint(config.decodedPublicKey());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("listener threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Builder; only {@code config} is required. */
    public static final class Builder {
        private final StreamConfig config;
        private CredentialsProvider credentials = CredentialsProvider.none();
        private VerificationService verificationService;
        private ArtifactDownloader downloader;
        private SignatureVerifier signatureVerifier;
        private ArtifactValidator validator = ArtifactValidator.none();
        private ClassLoader parentLoader;
        private BridgeProvider bridge;
        private StreamLogger log;
        private StreamingListener listener;
        private Map<String, String> clientInfo;

        private Builder(StreamConfig config) {
            this.config = Objects.requireNonNull(config, "config");
        }

        public Builder credentials(CredentialsProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            return this;
        }

        public Builder verificationService(VerificationService service) {
            this.verificationService = service;
            return this;
        }

        public Builder downloader(ArtifactDownloader downloader) {
            this.downloader = downloader;
            return this;
        }

        public Builder signatureVerifier(SignatureVerifier verifier) {
            this.signatureVerifier = verifier;
            return this;
        }

        public Builder validator(ArtifactValidator validator) {
            this.validator = validator == null ? ArtifactValidator.none() : validator;
            return this;
        }

        public Builder parentClassLoader(ClassLoader loader) {
            this.parentLoader = loader;
            return this;
        }

        public Builder bridge(BridgeProvider bridge) {
            this.bridge = bridge;
            return this;
        }

        public Builder logger(StreamLogger log) {
            this.log = log;
            return this;
        }

        public Builder listener(StreamingListener listener) {
            this.listener = listener;
            return this;
        }

        public Builder clientInfo(Map<String, String> clientInfo) {
            this.clientInfo = clientInfo;
            return this;
        }

        public StreamingRuntime build() {
            return new StreamingRuntime(this);
        }
    }
}
