package dev.modstreaming.core.config;

/** How the loader integration should run the streaming pipeline during game startup. */
public enum LoadMode {
    /** Load on a background thread; hooks are no-ops until the artifact arrives. */
    ASYNC,
    /** Block mod initialization until loading finished or {@code blockingTimeoutMillis} elapsed. */
    BLOCKING
}
