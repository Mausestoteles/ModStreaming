package dev.modstreaming.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Framework-wide constants. */
public final class ModStreaming {

    /** Resource path of the stream config embedded into public mod jars. */
    public static final String CONFIG_RESOURCE = "META-INF/modstreaming/stream-config.json";

    /** Resource path of the service metadata inside streamed jars. */
    public static final String SERVICES_RESOURCE = "META-INF/modstreaming/services.json";

    /** Version reported to backends. */
    public static final String VERSION = loadVersion();

    private ModStreaming() {
    }

    private static String loadVersion() {
        try (InputStream in = ModStreaming.class.getClassLoader().getResourceAsStream("modstreaming-core.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown";
    }
}
