package dev.modstreaming.core.publish;

import java.util.Map;

/**
 * Backend answer to a publish request.
 *
 * @param artifactId id assigned by the packager and confirmed by the backend
 * @param replaced   whether an artifact with the same coordinates was overwritten
 * @param attributes additional backend fields
 */
public record PublishResult(String artifactId, boolean replaced, Map<String, String> attributes) {
}
