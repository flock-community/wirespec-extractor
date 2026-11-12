plugins {
    java
    // Substituted by the test runner with the plugin version under test.
    id("community.flock.wirespec.extractor") version "@project.version@"
}

dependencies {
    // compileOnly, as when the runtime brings its own JAX-RS API: the extractor must still
    // load it, or every resource is skipped.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile> {
    // The extractor relies on parameter names for path / query bindings.
    options.compilerArgs.add("-parameters")
}

wirespecExtractor {
    basePackage.set("com.acme.api")
}
