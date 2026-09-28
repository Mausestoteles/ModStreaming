package dev.modstreaming.gradle.split;

import dev.modstreaming.api.ServiceBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which classes leave the public jar and which service bindings are generated.
 *
 * <p>A class is streamed when it is annotated with {@code @Streamed}, when its package is
 * ({@code package-info}), or when it is nested (member, local or anonymous) in a streamed class.
 * Only classes annotated directly and with {@code register = true} become service bindings.</p>
 */
public final class SplitPlan {

    private final Set<String> streamedClasses;
    private final List<ServiceBinding> bindings;
    private final List<String> warnings;

    private SplitPlan(Set<String> streamedClasses, List<ServiceBinding> bindings, List<String> warnings) {
        this.streamedClasses = Collections.unmodifiableSet(streamedClasses);
        this.bindings = Collections.unmodifiableList(bindings);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /** Internal names of every class that must be moved to the streamed jar. */
    public Set<String> streamedClasses() {
        return streamedClasses;
    }

    public boolean isStreamed(String internalName) {
        return streamedClasses.contains(internalName);
    }

    public List<ServiceBinding> bindings() {
        return bindings;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Distinct service interface binary names, in binding order. */
    public List<String> serviceInterfaces() {
        Set<String> out = new LinkedHashSet<>();
        bindings.forEach(b -> out.add(b.service()));
        return new ArrayList<>(out);
    }

    public static SplitPlan compute(List<ClassInfo> classes) {
        Map<String, ClassInfo> byName = new HashMap<>();
        Set<String> streamedPackages = new HashSet<>();
        for (ClassInfo info : classes) {
            byName.put(info.internalName(), info);
            if (info.packageInfo() && info.isAnnotated()) {
                streamedPackages.add(info.packageName());
            }
        }

        Set<String> streamed = new TreeSet<>();
        for (ClassInfo info : classes) {
            if (isStreamed(info, byName, streamedPackages, new HashSet<>())) {
                streamed.add(info.internalName());
            }
        }
        // Name based fallback for tools that strip InnerClasses/EnclosingMethod attributes.
        for (ClassInfo info : classes) {
            if (streamed.contains(info.internalName())) {
                continue;
            }
            String name = info.internalName();
            int dollar = name.indexOf('$');
            while (dollar > 0) {
                if (streamed.contains(name.substring(0, dollar))) {
                    streamed.add(name);
                    break;
                }
                dollar = name.indexOf('$', dollar + 1);
            }
        }

        List<ServiceBinding> bindings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ClassInfo info : classes) {
            if (!info.isAnnotated() || info.packageInfo() || !info.annotation().register()) {
                continue;
            }
            List<String> services = info.annotation().services().isEmpty()
                    ? info.interfaces()
                    : info.annotation().services();
            List<String> usable = new ArrayList<>();
            for (String service : services) {
                if (streamed.contains(service)) {
                    warnings.add(info.binaryName() + ": service interface " + service.replace('/', '.')
                            + " is itself streamed and cannot be used through the Bridge");
                } else if (isJdkType(service)) {
                    // java.* interfaces are never registered implicitly
                    if (!info.annotation().services().isEmpty()) {
                        usable.add(service);
                    }
                } else {
                    usable.add(service);
                }
            }
            if (usable.isEmpty()) {
                warnings.add(info.binaryName() + " is @Streamed but implements no public service interface; "
                        + "it is moved but not registered (use register = false to silence this)");
            }
            for (String service : usable) {
                bindings.add(new ServiceBinding(service.replace('/', '.'), info.binaryName()));
            }
        }
        return new SplitPlan(streamed, bindings, warnings);
    }

    private static boolean isStreamed(ClassInfo info, Map<String, ClassInfo> byName, Set<String> streamedPackages,
                                      Set<String> visiting) {
        if (info.isAnnotated()) {
            return true;
        }
        if (!info.packageInfo() && streamedPackages.contains(info.packageName())) {
            return true;
        }
        String outer = info.enclosingClass();
        if (outer == null || !visiting.add(info.internalName())) {
            return false;
        }
        ClassInfo outerInfo = byName.get(outer);
        return outerInfo != null && isStreamed(outerInfo, byName, streamedPackages, visiting);
    }

    private static boolean isJdkType(String internalName) {
        return internalName.startsWith("java/") || internalName.startsWith("javax/") || internalName.startsWith("jdk/");
    }
}
