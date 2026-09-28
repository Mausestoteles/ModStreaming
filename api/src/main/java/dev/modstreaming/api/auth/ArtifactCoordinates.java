package dev.modstreaming.api.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies one streamed artifact. Artifacts are distinguished by project, mod version,
 * Minecraft version and mod loader.
 */
public record ArtifactCoordinates(String projectId, String modVersion, String minecraftVersion, String loader) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}");

    public ArtifactCoordinates {
        projectId = requireSegment(projectId, "projectId");
        modVersion = requireSegment(modVersion, "modVersion");
        minecraftVersion = requireSegment(minecraftVersion, "minecraftVersion");
        loader = requireSegment(loader, "loader").toLowerCase(java.util.Locale.ROOT);
    }

    /** Human readable identifier such as {@code example-mod:1.0.0:1.21.1:fabric}. */
    public String key() {
        return projectId + ":" + modVersion + ":" + minecraftVersion + ":" + loader;
    }

    /** Returns {@code true} if every field of {@code other} equals this one. */
    public boolean matches(ArtifactCoordinates other) {
        return equals(other);
    }

    /** Validates that a coordinate segment is safe to use in paths, URLs and file names. */
    public static String requireSegment(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (!SEGMENT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(name + " contains illegal characters or is empty: '" + value + "'");
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return key();
    }
}
