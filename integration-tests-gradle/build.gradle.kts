plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "End-to-end tests that drive the Gradle plugin against fixture Gradle/Spring/Kotlin projects via TestKit."

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(gradleTestKit())
}

// Path used by the test runner to locate the IT-local Maven repo where the
// Gradle plugin + extractor-core have just been published. Shared with the
// :integration-tests (Maven) module — both write into the same `it-repo`.
val itRepoDir = rootProject.layout.buildDirectory.dir("it-repo")

// What the fixture builds run: the Gradle plugin with extractor-core and their runtime
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
    itPlugin(project(":extractor-gradle-plugin"))
}

tasks.test {
    useJUnitPlatform()

    // Publish both the plugin jar AND the Gradle plugin marker artifact, plus
    // extractor-core (transitive dependency), into the project-local repo
    // before the IT runs. Marker is what makes `plugins { id("...") }` resolve.
    dependsOn(":extractor-gradle-plugin:publishAllPublicationsToItLocalRepository")
    dependsOn(":extractor-core:publishMavenPublicationToItLocalRepository")

    inputs.files(itPlugin).withPropertyName("itPlugin").withNormalizer(ClasspathNormalizer::class.java)
    // The fixture projects are passed by path only, so their sources are tracked explicitly.
    inputs.dir("src/it").withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("it.pluginVersion", project.version.toString())
    systemProperty("it.repo", itRepoDir.get().asFile.absolutePath)
    systemProperty("it.fixturesRoot", layout.projectDirectory.dir("src/it").asFile.absolutePath)
    systemProperty("it.workRoot", layout.buildDirectory.dir("it-work").get().asFile.absolutePath)
    systemProperty("it.testKitDir", layout.buildDirectory.dir("it-testkit").get().asFile.absolutePath)

    // `gradle` is invoked via TestKit (in-process). Show its output only on failure.
    testLogging {
        showStandardStreams = false
        events("failed")
    }
}
