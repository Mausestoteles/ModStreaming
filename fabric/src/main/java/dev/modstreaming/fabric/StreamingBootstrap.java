package dev.modstreaming.fabric;

import dev.modstreaming.core.config.LoadMode;
import dev.modstreaming.core.log.StreamLogger;
import dev.modstreaming.core.runtime.LoadResult;
import dev.modstreaming.core.runtime.LoadStatus;
import dev.modstreaming.core.runtime.StreamingRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loader-independent orchestration used by the Fabric entrypoint: runs one
 * {@link StreamingRuntime} per streamed mod, either asynchronously or blocking with a timeout,
 * and dispatches results to the mods' entrypoints. Nothing in here can crash the game: every
 * failure ends as a logged {@link LoadResult}.
 */
public final class StreamingBootstrap {

    private final StreamLogger log;
    private final ClassLoader parentLoader;
    private final ExecutorService executor;
    private final Map<String, StreamedMod> mods = new LinkedHashMap<>();
    private final Map<String, StreamingRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<LoadResult>> pending = new ConcurrentHashMap<>();

    public StreamingBootstrap(StreamLogger log, ClassLoader parentLoader) {
        this(log, parentLoader, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ModStreaming-Loader");
            t.setDaemon(true);
            return t;
        }));
    }

    public StreamingBootstrap(StreamLogger log, ClassLoader parentLoader, ExecutorService executor) {
        this.log = Objects.requireNonNull(log, "log");
        this.parentLoader = Objects.requireNonNull(parentLoader, "parentLoader");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Registers the mods and starts loading according to each mod's {@link LoadMode}. */
    public void start(List<StreamedMod> streamedMods) {
        for (StreamedMod mod : streamedMods) {
            if (mods.putIfAbsent(mod.projectId(), mod) != null) {
                log.warn("project " + mod.projectId() + " is declared by more than one mod; ignoring " + mod.modId());
                continue;
            }
            CompletableFuture<LoadResult> future = load(mod);
            if (mod.config().loadMode() == LoadMode.BLOCKING) {
                awaitBlocking(mod, future);
            }
        }
    }

    /** Runs the pipeline again, e.g. after the user entered credentials. */
    public CompletableFuture<LoadResult> reload(String projectId) {
        StreamedMod mod = mods.get(projectId);
        if (mod == null) {
            return CompletableFuture.completedFuture(LoadResult.of(LoadStatus.INVALID_CONFIG, "unknown project " + projectId));
        }
        CompletableFuture<LoadResult> running = pending.get(projectId);
        if (running != null && !running.isDone()) {
            return running;
        }
        StreamingRuntime previous = runtimes.get(projectId);
        if (previous != null) {
            previous.unload();
        }
        return load(mod);
    }

    public Optional<LoadResult> result(String projectId) {
        StreamingRuntime runtime = runtimes.get(projectId);
        return runtime == null ? Optional.empty() : runtime.lastResult();
    }

    public Map<String, StreamedMod> mods() {
        return Collections.unmodifiableMap(mods);
    }

    public boolean isLoaded(String projectId) {
        return result(projectId).map(LoadResult::isLoaded).orElse(false);
    }

    /** Blocks until every pending load finished (tests and blocking integrations). */
    public void awaitAll(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        CompletableFuture<?>[] all = pending.values().toArray(new CompletableFuture<?>[0]);
        try {
            CompletableFuture.allOf(all).get(timeout, unit);
        } catch (ExecutionException e) {
            // individual results already carry the failure
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<LoadResult> load(StreamedMod mod) {
        StreamingRuntime runtime = StreamingRuntime.builder(mod.config())
                .credentials(mod.credentials())
                .parentClassLoader(parentLoader)
                .logger(log)
                .build();
        runtimes.put(mod.projectId(), runtime);
        CompletableFuture<LoadResult> future = runtime.loadAsync(executor)
                .exceptionally(t -> LoadResult.of(LoadStatus.FAILED, "unexpected error: " + t, t))
                .thenApply(result -> {
                    dispatch(mod, result);
                    return result;
                });
        pending.put(mod.projectId(), future);
        return future;
    }

    private void awaitBlocking(StreamedMod mod, CompletableFuture<LoadResult> future) {
        long millis = mod.config().blockingTimeout().toMillis();
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn(mod.projectId() + ": streaming did not finish within " + millis + " ms; continuing without it "
                    + "(the artifact may still arrive later)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error(mod.projectId() + ": streaming failed", e.getCause());
        }
    }

    private void dispatch(StreamedMod mod, LoadResult result) {
        switch (result.status()) {
            case LOADED -> log.info(mod.projectId() + ": " + result.message());
            case DENIED -> log.info(mod.projectId() + ": access denied by backend (" + result.message() + "); streamed features stay disabled");
            case UNAVAILABLE -> log.warn(mod.projectId() + ": backend unavailable (" + result.message() + "); streamed features stay disabled");
            default -> log.error(mod.projectId() + ": " + result.status() + " - " + result.message(), result.cause());
        }
        for (ModStreamingEntrypoint entrypoint : mod.entrypoints()) {
            try {
                entrypoint.onResult(result);
            } catch (Throwable t) {
                log.error(mod.projectId() + ": entrypoint " + entrypoint.getClass().getName() + " threw", t);
            }
        }
    }
}
