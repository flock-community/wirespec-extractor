plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "End-to-end tests that drive the published Maven plugin against fixture Spring/Kotlin projects via `mvn`."

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
}

// Path used by the test runner to locate the IT-local Maven repo where the
// plugin module has just been published. Shared by all tests and tasks here.
val itRepoDir = rootProject.layout.buildDirectory.dir("it-repo")

// What the fixture builds run: the Maven plugin with extractor-core and their runtime
// dependencies. `test` publishes it to `it-repo` first, but does not track that directory, so
// it is declared as an input: a change to any of it reruns the ITs instead of `test` being
// skipped as up-to-date (or restored from the build cache).
val itPlugin by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    itPlugin(project(":extractor-maven-plugin"))
}

tasks.test {
    useJUnitPlatform()

    // The fixture builds need our just-built plugin AND its :extractor-core
    // dependency to be discoverable in a local Maven repo. Publish both into
    // a project-local repo (NOT ~/.m2) so we don't pollute the user's
    // environment and tests stay reproducible.
    dependsOn(":extractor-maven-plugin:publishMavenPublicationToItLocalRepository")
    dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")

    inputs.files(itPlugin).withPropertyName("itPlugin").withNormalizer(ClasspathNormalizer::class.java)
    // The fixture projects are passed by path only, so their sources are tracked explicitly.
    inputs.dir("src/it").withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("it.pluginVersion", project.version.toString())
    systemProperty("it.repo", itRepoDir.get().asFile.absolutePath)
    systemProperty("it.fixturesRoot", layout.projectDirectory.dir("src/it").asFile.absolutePath)
    systemProperty("it.workRoot", layout.buildDirectory.dir("it-work").get().asFile.absolutePath)

    // `mvn` is invoked as an external process; show its stdout/stderr only on
    // failure to keep passing-test output quiet.
    testLogging {
        showStandardStreams = false
        events("failed")
    }
}
