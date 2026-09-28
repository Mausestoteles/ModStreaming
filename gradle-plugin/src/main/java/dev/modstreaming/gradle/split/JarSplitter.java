package dev.modstreaming.gradle.split;

import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.artifact.ServiceMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Splits one jar into a cleaned public jar and a streamed jar. Loader-agnostic and usable outside
 * Gradle (for example from a CI script).
 */
public final class JarSplitter {

    /** Options for a split. */
    public record Options(String streamConfigJson, List<String> streamedResourcePatterns) {
        public Options {
            Objects.requireNonNull(streamConfigJson, "streamConfigJson");
            streamedResourcePatterns = streamedResourcePatterns == null ? List.of() : List.copyOf(streamedResourcePatterns);
        }
    }

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    private JarSplitter() {
    }

    public static SplitResult split(Path inputJar, Path publicJar, Path streamedJar, Options options) throws IOException {
        Map<String, byte[]> entries = readJar(inputJar);

        // 1. Scan every class.
        List<ClassInfo> classes = new ArrayList<>();
        Map<String, String> entryByClass = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                ClassInfo info = StreamedScanner.scan(e.getValue());
                classes.add(info);
                entryByClass.put(info.internalName(), name);
            }
        }
        SplitPlan plan = SplitPlan.compute(classes);

        // 2. Decide entry placement.
        List<Pattern> resourcePatterns = options.streamedResourcePatterns().stream().map(JarSplitter::globToRegex).toList();
        Map<String, byte[]> publicEntries = new LinkedHashMap<>();
        Map<String, byte[]> streamedEntries = new LinkedHashMap<>();
        List<String> streamedResources = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.equals(ModStreaming.CONFIG_RESOURCE) || name.equals(ModStreaming.SERVICES_RESOURCE)) {
                continue; // regenerated below
            }
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                String internal = name.substring(0, name.length() - ".class".length());
                if (plan.isStreamed(internal)) {
                    streamedEntries.put(name, e.getValue());
                    continue;
                }
            } else if (!name.equals(MANIFEST_ENTRY) && matchesAny(name, resourcePatterns)) {
                streamedEntries.put(name, e.getValue());
                streamedResources.add(name);
                continue;
            }
            publicEntries.put(name, e.getValue());
        }

        // 3. Leak check on the remaining public classes.
        Set<ReferenceChecker.Leak> leaks = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        for (Map.Entry<String, byte[]> e : publicEntries.entrySet()) {
            String name = e.getKey();
            if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                String internal = name.substring(0, name.length() - ".class".length());
                leaks.addAll(ReferenceChecker.findLeaks(internal, e.getValue(), plan.streamedClasses()));
            }
        }

        // 4. Embed metadata.
        publicEntries.put(ModStreaming.CONFIG_RESOURCE, options.streamConfigJson().getBytes(StandardCharsets.UTF_8));
        streamedEntries.put(ModStreaming.SERVICES_RESOURCE, ServiceMetadata.toJson(plan.bindings()).getBytes(StandardCharsets.UTF_8));
        if (!streamedEntries.containsKey(MANIFEST_ENTRY)) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("ModStreaming-Artifact", "streamed");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            manifest.write(bos);
            streamedEntries.put(MANIFEST_ENTRY, bos.toByteArray());
        }

        // 5. Write both jars (manifest first, as the jar spec expects).
        writeJar(publicJar, publicEntries);
        writeJar(streamedJar, streamedEntries);

        List<String> streamedClassNames = plan.streamedClasses().stream().map(n -> n.replace('/', '.')).toList();
        return new SplitResult(streamedClassNames, streamedResources, plan.bindings(), leaks, plan.warnings());
    }

    public static Map<String, byte[]> readJar(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    public static void writeJar(Path target, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] manifest = entries.get(MANIFEST_ENTRY);
            if (manifest != null) {
                putEntry(zip, MANIFEST_ENTRY, manifest);
            }
            Set<String> dirs = new TreeSet<>();
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (e.getKey().equals(MANIFEST_ENTRY)) {
                    continue;
                }
                addParentDirs(zip, e.getKey(), dirs);
                putEntry(zip, e.getKey(), e.getValue());
            }
        }
    }

    private static void addParentDirs(ZipOutputStream zip, String name, Set<String> dirs) throws IOException {
        int idx = 0;
        while ((idx = name.indexOf('/', idx + 1)) > 0) {
            String dir = name.substring(0, idx + 1);
            if (dirs.add(dir)) {
                ZipEntry entry = new ZipEntry(dir);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.closeEntry();
            }
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static boolean matchesAny(String name, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Converts an Ant-style glob ({@code **}, {@code *}, {@code ?}) to a regex over entry names. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                        if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                            i++;
                            sb.append("(?:/)?");
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }
}
