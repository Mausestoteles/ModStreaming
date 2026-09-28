package dev.modstreaming.gradle.split;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Set;
import java.util.TreeSet;

/**
 * Finds references from public classes to streamed classes. Such references would turn into
 * {@code NoClassDefFoundError}s at runtime, because streamed classes are only reachable through
 * the Bridge.
 */
public final class ReferenceChecker {

    /** A public class referencing a streamed class. */
    public record Leak(String publicClass, String streamedClass) {
        @Override
        public String toString() {
            return publicClass.replace('/', '.') + " -> " + streamedClass.replace('/', '.');
        }
    }

    private ReferenceChecker() {
    }

    /** Every internal class name referenced anywhere in the class file (descriptors, signatures, code, annotations). */
    public static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> referenced = new TreeSet<>();
        Remapper recorder = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                referenced.add(internalName);
                return internalName;
            }
        };
        // A ClassWriter sink makes the remapper visit fields, methods and code, so every reference is seen.
        new ClassReader(classBytes).accept(new ClassRemapper(new ClassWriter(0), recorder), 0);
        return referenced;
    }

    public static Set<Leak> findLeaks(String publicClass, byte[] classBytes, Set<String> streamedClasses) {
        Set<Leak> leaks = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        for (String ref : referencedClasses(classBytes)) {
            if (!ref.equals(publicClass) && streamedClasses.contains(ref)) {
                leaks.add(new Leak(publicClass, ref));
            }
        }
        return leaks;
    }
}
