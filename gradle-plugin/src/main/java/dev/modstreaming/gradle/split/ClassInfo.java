package dev.modstreaming.gradle.split;

import java.util.List;

/**
 * Facts about one class file that matter for splitting.
 *
 * @param internalName    e.g. {@code com/example/impl/SecretGreeting}
 * @param enclosingClass  internal name of the lexically enclosing class ({@code null} for top level)
 * @param interfaces      directly implemented interfaces (internal names)
 * @param annotation      the {@code @Streamed} annotation, if present
 * @param packageInfo     {@code true} for {@code package-info} classes
 */
public record ClassInfo(String internalName,
                        String enclosingClass,
                        List<String> interfaces,
                        StreamedAnnotation annotation,
                        boolean packageInfo) {

    /** Values of a {@code @Streamed} annotation. */
    public record StreamedAnnotation(List<String> services, boolean register) {
    }

    public String binaryName() {
        return internalName.replace('/', '.');
    }

    public String packageName() {
        int idx = internalName.lastIndexOf('/');
        return idx < 0 ? "" : internalName.substring(0, idx);
    }

    public boolean isAnnotated() {
        return annotation != null;
    }
}
