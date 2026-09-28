package dev.modstreaming.gradle.split;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamedScannerTest {

    @Test
    void detectsAnnotationAndInterfaces() throws Exception {
        ClassInfo info = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/SecretImpl.class"));
        assertEquals("dev/modstreaming/gradle/fixture/secret/SecretImpl", info.internalName());
        assertTrue(info.isAnnotated());
        assertTrue(info.annotation().register());
        assertTrue(info.annotation().services().isEmpty());
        assertEquals(List.of("dev/modstreaming/gradle/fixture/PublicService"), info.interfaces());
        assertNull(info.enclosingClass());
        assertFalse(info.packageInfo());
    }

    @Test
    void readsExplicitServicesAndRegisterFlag() throws Exception {
        ClassInfo explicit = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/ExplicitImpl.class"));
        assertEquals(List.of("dev/modstreaming/gradle/fixture/PublicService"), explicit.annotation().services());
        ClassInfo moved = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/MovedOnly.class"));
        assertFalse(moved.annotation().register());
    }

    @Test
    void resolvesEnclosingClasses() throws Exception {
        ClassInfo nested = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/SecretImpl$Nested.class"));
        assertEquals("dev/modstreaming/gradle/fixture/secret/SecretImpl", nested.enclosingClass());
        ClassInfo anonymous = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/SecretImpl$1.class"));
        assertEquals("dev/modstreaming/gradle/fixture/secret/SecretImpl", anonymous.enclosingClass());
        ClassInfo local = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "secret/SecretImpl$1$1Local.class"));
        assertEquals("dev/modstreaming/gradle/fixture/secret/SecretImpl$1", local.enclosingClass());
        assertFalse(nested.isAnnotated());
    }

    @Test
    void detectsPackageInfo() throws Exception {
        ClassInfo pkg = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "pkg/package-info.class"));
        assertTrue(pkg.packageInfo());
        assertTrue(pkg.isAnnotated());
        assertEquals("dev/modstreaming/gradle/fixture/pkg", pkg.packageName());
    }

    @Test
    void publicClassesAreNotStreamed() throws Exception {
        ClassInfo hook = StreamedScanner.scan(FixtureJars.bytes(FixtureJars.PKG + "PublicHook.class"));
        assertFalse(hook.isAnnotated());
        assertTrue(ReferenceChecker.referencedClasses(FixtureJars.bytes(FixtureJars.PKG + "PublicHook.class"))
                .contains("dev/modstreaming/api/Bridge"));
    }
}
