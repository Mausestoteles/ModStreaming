package dev.modstreaming.gradle.split;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the facts needed for splitting from class bytes using ASM. Works on any class file,
 * including dev-mapped Loom output, because it only looks at names, interfaces, the
 * {@code InnerClasses}/{@code EnclosingMethod} attributes and the {@code @Streamed} annotation.
 */
public final class StreamedScanner {

    /** Descriptor of {@code dev.modstreaming.api.Streamed}. */
    public static final String STREAMED_DESCRIPTOR = "Ldev/modstreaming/api/Streamed;";

    private StreamedScanner() {
    }

    public static ClassInfo scan(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        Visitor visitor = new Visitor();
        reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.toInfo();
    }

    private static final class Visitor extends ClassVisitor {
        private String name;
        private List<String> interfaces = List.of();
        private String outerFromInnerClasses;
        private String outerFromEnclosingMethod;
        private boolean annotated;
        private final List<String> services = new ArrayList<>();
        private boolean register = true;

        Visitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public void visitOuterClass(String owner, String methodName, String descriptor) {
            this.outerFromEnclosingMethod = owner;
        }

        @Override
        public void visitInnerClass(String innerName, String outerName, String innerSimpleName, int access) {
            if (innerName.equals(name) && outerName != null) {
                this.outerFromInnerClasses = outerName;
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!STREAMED_DESCRIPTOR.equals(descriptor)) {
                return null;
            }
            annotated = true;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String attribute, Object value) {
                    if ("register".equals(attribute) && value instanceof Boolean b) {
                        register = b;
                    }
                }

                @Override
                public AnnotationVisitor visitArray(String attribute) {
                    if (!"value".equals(attribute)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            if (value instanceof Type type) {
                                services.add(type.getInternalName());
                            }
                        }
                    };
                }
            };
        }

        ClassInfo toInfo() {
            String enclosing = outerFromInnerClasses != null ? outerFromInnerClasses : outerFromEnclosingMethod;
            boolean packageInfo = name.endsWith("/package-info") || name.equals("package-info");
            ClassInfo.StreamedAnnotation annotation = annotated
                    ? new ClassInfo.StreamedAnnotation(List.copyOf(services), register)
                    : null;
            return new ClassInfo(name, enclosing, interfaces, annotation, packageInfo);
        }
    }
}
