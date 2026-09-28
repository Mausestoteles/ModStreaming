package dev.modstreaming.core.log;

/**
 * Minimal logging abstraction so that {@code core} stays free of loader or logging framework
 * dependencies. Implementations must never receive credentials, tokens or keys; the runtime
 * only passes non-sensitive diagnostics.
 */
public interface StreamLogger {

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);

    default void error(String message) {
        error(message, null);
    }

    /** Logger backed by {@link System.Logger}. */
    static StreamLogger system() {
        return SystemStreamLogger.INSTANCE;
    }

    /** Logger that discards everything. */
    static StreamLogger silent() {
        return new StreamLogger() {
            @Override
            public void debug(String message) {
            }

            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message, Throwable error) {
            }
        };
    }

    /** Prefixes every message, e.g. with the project id. */
    default StreamLogger withPrefix(String prefix) {
        StreamLogger delegate = this;
        String p = "[" + prefix + "] ";
        return new StreamLogger() {
            @Override
            public void debug(String message) {
                delegate.debug(p + message);
            }

            @Override
            public void info(String message) {
                delegate.info(p + message);
            }

            @Override
            public void warn(String message) {
                delegate.warn(p + message);
            }

            @Override
            public void error(String message, Throwable error) {
                delegate.error(p + message, error);
            }
        };
    }
}
