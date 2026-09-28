package dev.modstreaming.core.runtime;

import dev.modstreaming.api.auth.VerificationResult;

/** Observer for the streaming pipeline. All methods have no-op defaults and must not throw. */
public interface StreamingListener {

    default void onVerificationStarted(String projectId) {
    }

    default void onVerified(String projectId, VerificationResult result) {
    }

    default void onDenied(String projectId, VerificationResult result) {
    }

    default void onDownloaded(String projectId, int bytes) {
    }

    default void onLoaded(String projectId, LoadResult result) {
    }

    default void onFailed(String projectId, LoadResult result) {
    }

    /** Called with every terminal result. */
    default void onFinished(String projectId, LoadResult result) {
    }

    static StreamingListener none() {
        return new StreamingListener() {
        };
    }
}
