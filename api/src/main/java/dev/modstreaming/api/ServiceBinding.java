package dev.modstreaming.api;

import java.util.Objects;

/**
 * Describes which streamed implementation class provides which service interface.
 *
 * @param service        binary name of the service interface (visible in the public jar)
 * @param implementation binary name of the streamed implementation class
 */
public record ServiceBinding(String service, String implementation) {

    public ServiceBinding {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(implementation, "implementation");
        if (service.isBlank() || implementation.isBlank()) {
            throw new IllegalArgumentException("service and implementation must not be blank");
        }
    }
}
