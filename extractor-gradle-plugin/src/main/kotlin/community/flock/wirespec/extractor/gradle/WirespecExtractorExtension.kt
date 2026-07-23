package community.flock.wirespec.extractor.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * Build-script DSL for the Gradle plugin:
 *
 * ```kotlin
 * wirespecExtractor {
 *     outputDir.set(layout.buildDirectory.dir("wirespec"))   // default
 *     basePackage.set("com.acme.api")
 *     extractSpring.set(true)    // default — Spring MVC, DSL routes, messaging
 *     extractOpenApi.set(true)   // default — JAX-RS + swagger annotations
 *     extractKtor.set(true)      // default — Ktor server routing + client calls
 *     generateJar.set(true)      // default false — bundle .ws files into a jar
 *     jarClassifier.set("wirespec")  // default — classifier for the bundled jar
 * }
 * ```
 */
abstract class WirespecExtractorExtension {
    abstract val outputDir: DirectoryProperty
    abstract val basePackage: Property<String>

    /** Extract Spring MVC controllers, functional-DSL routes, and messaging channels. Default `true`. */
    abstract val extractSpring: Property<Boolean>

    /** Extract JAX-RS resources whose OpenAPI detail is driven by swagger annotations. Default `true`. */
    abstract val extractOpenApi: Property<Boolean>

    /** Extract Ktor server routing trees and Ktor client request calls. Default `true`. */
    abstract val extractKtor: Property<Boolean>

    /**
     * When `true`, bundle the emitted `.ws` files into a jar (task `wirespecJar`)
     * and, if `maven-publish` is applied, add it to the project's publications
     * under [jarClassifier] so `publish` ships it. Default `false`.
     */
    abstract val generateJar: Property<Boolean>

    /** Classifier for the bundled Wirespec jar. Default `wirespec`. */
    abstract val jarClassifier: Property<String>
}
