package dev.modstreaming.core.client;

import dev.modstreaming.core.ModStreaming;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Non-sensitive client environment information sent with verification requests. */
public final class ClientInfo {

    private ClientInfo() {
    }

    /** Framework version, Java major version, coarse OS family and the mod loader. */
    public static Map<String, String> collect(String loader) {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("frameworkVersion", ModStreaming.VERSION);
        info.put("javaVersion", String.valueOf(Runtime.version().feature()));
        info.put("os", osFamily());
        if (loader != null) {
            info.put("loader", loader);
        }
        return info;
    }

    private static String osFamily() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("nux") || os.contains("nix")) {
            return "linux";
        }
        return "other";
    }
}
