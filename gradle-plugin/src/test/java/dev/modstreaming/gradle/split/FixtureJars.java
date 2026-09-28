package dev.modstreaming.gradle.split;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FixtureJars {

    static final String PKG = "dev/modstreaming/gradle/fixture/";

    static final List<String> ALL = List.of(
            PKG + "PublicService.class",
            PKG + "PublicHook.class",
            PKG + "secret/SecretImpl.class",
            PKG + "secret/SecretImpl$Nested.class",
            PKG + "secret/SecretImpl$InnerMember.class",
            PKG + "secret/SecretImpl$1.class",
            PKG + "secret/SecretImpl$1$1Local.class",
            PKG + "secret/MovedOnly.class",
            PKG + "secret/ExplicitImpl.class",
            PKG + "pkg/package-info.class",
            PKG + "pkg/PkgClass.class");

    private FixtureJars() {
    }

    static byte[] bytes(String entry) throws IOException {
        try (InputStream in = FixtureJars.class.getClassLoader().getResourceAsStream(entry)) {
            if (in == null) {
                throw new IOException("missing fixture " + entry);
            }
            return in.readAllBytes();
        }
    }

    static Map<String, byte[]> entries(List<String> classEntries) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        map.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes());
        for (String e : classEntries) {
            map.put(e, bytes(e));
        }
        map.put("fabric.mod.json", "{\"id\":\"demo\"}".getBytes());
        map.put("assets/demo/public.txt", "public".getBytes());
        map.put("assets/demo/secret/model.json", "{}".getBytes());
        return map;
    }
}
