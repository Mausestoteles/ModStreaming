package dev.modstreaming.core.runtime;

import dev.modstreaming.core.loader.InMemoryClassLoader;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of {@link StreamingRuntime#load()}.
 *
 * @param status      outcome category
 * @param message     human readable, non-sensitive description
 * @param artifactId  id of the loaded artifact, if any
 * @param services    binary names of registered service interfaces
 * @param attributes  backend supplied attributes from the verification response
 * @param cause       underlying exception for FAILED/UNAVAILABLE, if any
 * @param classLoader the in-memory loader holding the streamed classes (LOADED only)
 */
public record LoadResult(LoadStatus status,
                         String message,
                         String artifactId,
                         List<String> services,
                         Map<String, String> attributes,
                         Throwable cause,
                         InMemoryClassLoader classLoader) {

    public LoadResult {
        Objects.requireNonNull(status, "status");
        message = message == null ? status.name() : message;
        services = services == null ? List.of() : List.copyOf(services);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static LoadResult of(LoadStatus status, String message) {
        return new LoadResult(status, message, null, List.of(), Map.of(), null, null);
    }

    public static LoadResult of(LoadStatus status, String message, Throwable cause) {
        return new LoadResult(status, message, null, List.of(), Map.of(), cause, null);
    }

    public boolean isLoaded() {
        return status.isLoaded();
    }

    public Optional<InMemoryClassLoader> loader() {
        return Optional.ofNullable(classLoader);
    }

    @Override
    public String toString() {
        return status + ": " + message + (artifactId != null ? " [" + artifactId + "]" : "");
    }
}
