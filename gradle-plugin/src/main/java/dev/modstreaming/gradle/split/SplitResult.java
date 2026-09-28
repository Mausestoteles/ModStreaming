package dev.modstreaming.gradle.split;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modstreaming.api.ServiceBinding;

import java.util.List;
import java.util.Set;

/**
 * What a split produced.
 *
 * @param streamedClasses  binary names moved to the streamed jar
 * @param streamedResources non-class entries moved to the streamed jar
 * @param bindings         generated service bindings
 * @param leaks            public → streamed references found by the {@link ReferenceChecker}
 * @param warnings         non-fatal findings
 */
public record SplitResult(List<String> streamedClasses,
                          List<String> streamedResources,
                          List<ServiceBinding> bindings,
                          Set<ReferenceChecker.Leak> leaks,
                          List<String> warnings) {

    public boolean hasStreamedContent() {
        return !streamedClasses.isEmpty() || !streamedResources.isEmpty();
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        JsonArray classes = new JsonArray();
        streamedClasses.forEach(classes::add);
        o.add("streamedClasses", classes);
        JsonArray resources = new JsonArray();
        streamedResources.forEach(resources::add);
        o.add("streamedResources", resources);
        JsonArray services = new JsonArray();
        for (ServiceBinding b : bindings) {
            JsonObject e = new JsonObject();
            e.addProperty("service", b.service());
            e.addProperty("implementation", b.implementation());
            services.add(e);
        }
        o.add("services", services);
        JsonArray leakArray = new JsonArray();
        leaks.forEach(l -> leakArray.add(l.toString()));
        o.add("publicReferencesToStreamedClasses", leakArray);
        JsonArray warningArray = new JsonArray();
        warnings.forEach(warningArray::add);
        o.add("warnings", warningArray);
        return new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }
}
