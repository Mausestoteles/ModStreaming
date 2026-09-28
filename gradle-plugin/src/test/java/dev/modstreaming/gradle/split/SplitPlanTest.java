package dev.modstreaming.gradle.split;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitPlanTest {

    private static ClassInfo cls(String name, String outer, List<String> ifaces, ClassInfo.StreamedAnnotation ann) {
        return new ClassInfo(name, outer, ifaces, ann, name.endsWith("package-info"));
    }

    @Test
    void nestedChainAndPackageAnnotationAreFollowed() {
        SplitPlan plan = SplitPlan.compute(List.of(
                cls("a/Svc", null, List.of(), null),
                cls("a/impl/Impl", null, List.of("a/Svc", "java/lang/Runnable"), new ClassInfo.StreamedAnnotation(List.of(), true)),
                cls("a/impl/Impl$1", "a/impl/Impl", List.of(), null),
                cls("a/impl/Impl$1$Deep", "a/impl/Impl$1", List.of(), null),
                cls("a/p/package-info", null, List.of(), new ClassInfo.StreamedAnnotation(List.of(), true)),
                cls("a/p/Helper", null, List.of(), null),
                cls("a/Hook", null, List.of(), null)));
        assertTrue(plan.isStreamed("a/impl/Impl"));
        assertTrue(plan.isStreamed("a/impl/Impl$1"));
        assertTrue(plan.isStreamed("a/impl/Impl$1$Deep"));
        assertTrue(plan.isStreamed("a/p/Helper"));
        assertTrue(plan.isStreamed("a/p/package-info"));
        assertFalse(plan.isStreamed("a/Hook"));
        assertFalse(plan.isStreamed("a/Svc"));
        assertEquals(1, plan.bindings().size());
        assertEquals("a.Svc", plan.bindings().get(0).service());
        assertEquals("a.impl.Impl", plan.bindings().get(0).implementation());
        assertEquals(List.of("a.Svc"), plan.serviceInterfaces());
    }

    @Test
    void namePrefixFallbackWhenAttributesAreMissing() {
        SplitPlan plan = SplitPlan.compute(List.of(
                cls("a/Impl", null, List.of(), new ClassInfo.StreamedAnnotation(List.of(), true)),
                cls("a/Impl$Stripped", null, List.of(), null)));
        assertTrue(plan.isStreamed("a/Impl$Stripped"));
        assertEquals(1, plan.warnings().size(), "no service interface -> warning");
    }

    @Test
    void streamedInterfacesAreNotServicesAndExplicitJdkTypesAreAllowed() {
        SplitPlan plan = SplitPlan.compute(List.of(
                cls("a/SecretApi", null, List.of(), new ClassInfo.StreamedAnnotation(List.of(), false)),
                cls("a/Impl", null, List.of("a/SecretApi"), new ClassInfo.StreamedAnnotation(List.of(), true)),
                cls("a/Task", null, List.of("java/lang/Runnable"), new ClassInfo.StreamedAnnotation(List.of("java/lang/Runnable"), true))));
        assertTrue(plan.isStreamed("a/SecretApi"));
        assertEquals(1, plan.bindings().size());
        assertEquals("java.lang.Runnable", plan.bindings().get(0).service());
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("SecretApi")));
    }
}
