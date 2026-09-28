package dev.modstreaming.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks implementation logic that must not ship inside the public mod jar.
 *
 * <p>The ModStreaming Gradle plugin removes every {@code @Streamed} class (including all of its
 * inner, nested, local and anonymous classes) from the public jar and packages it into a separate,
 * signed and encrypted artifact. The artifact is downloaded at runtime, verified, decrypted and
 * loaded through an in-memory class loader. Implementations are then registered in the
 * {@link Bridge} under the service interfaces they implement.</p>
 *
 * <p>Placing the annotation on a {@code package-info.java} moves every class of that package.
 * Only classes that carry the annotation directly are registered as services.</p>
 *
 * <pre>{@code
 * @Streamed
 * public final class SecretGreetingService implements GreetingService {
 *     public String greet(String name) { return "Hello, " + name; }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface Streamed {

    /**
     * Service interfaces under which the annotated class is registered in the {@link Bridge}.
     * When empty, every directly implemented interface that is not part of the JDK is used.
     */
    Class<?>[] value() default {};

    /**
     * Whether an instance of the annotated class is created and registered in the {@link Bridge}.
     * Set to {@code false} for helper classes that should only be moved out of the public jar.
     */
    boolean register() default true;
}
