package dev.modstreaming.core.loader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * In-memory view of a jar: entry path → bytes. Never touches the file system.
 */
public final class JarContents {

    private final Map<String, byte[]> entries;

    private JarContents(Map<String, byte[]> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static JarContents of(Map<String, byte[]> entries) {
        return new JarContents(new LinkedHashMap<>(entries));
    }

    /** Parses jar bytes, rejecting absolute or traversing entry names and enforcing a size limit. */
    public static JarContents parse(byte[] jar, long maxTotalBytes) throws IOException {
        Objects.requireNonNull(jar, "jar");
        Map<String, byte[]> map = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            byte[] buf = new byte[16 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new IOException("illegal jar entry name: " + name);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buf)) > 0) {
                    total += n;
                    if (total > maxTotalBytes) {
                        throw new IOException("jar exceeds the limit of " + maxTotalBytes + " bytes");
                    }
                    out.write(buf, 0, n);
                }
                map.put(name, out.toByteArray());
            }
        }
        return new JarContents(map);
    }

    public static JarContents parse(byte[] jar) throws IOException {
        return parse(jar, 256L * 1024 * 1024);
    }

    public Map<String, byte[]> entries() {
        return entries;
    }

    public byte[] get(String path) {
        return entries.get(path);
    }

    public boolean contains(String path) {
        return entries.containsKey(path);
    }

    public int size() {
        return entries.size();
    }

    /** Binary class name for an entry path, or {@code null} if it is not a class file. */
    public static String classNameOf(String entryPath) {
        if (!entryPath.endsWith(".class") || entryPath.startsWith("META-INF/")) {
            return null;
        }
        String name = entryPath.substring(0, entryPath.length() - ".class".length());
        if (name.equals("module-info") || name.endsWith("/module-info")) {
            return null;
        }
        return name.replace('/', '.');
    }
}
