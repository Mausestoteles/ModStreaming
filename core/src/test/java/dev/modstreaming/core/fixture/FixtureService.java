package dev.modstreaming.core.fixture;

/** "Public side" service interface used by the tests. Lives in the parent class loader. */
public interface FixtureService {
    String greet(String name);

    int innerValue();
}
