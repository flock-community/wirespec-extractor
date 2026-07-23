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
 *
 *     // Jar packaging — jarEnabled and jarPath belong together:
 *     jarEnabled.set(true)         // default false — bundle .ws files into a jar
 *     jarPath.set("com/acme/api")  // in-jar directory; default: the project name (artifactId)
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
     * Bundle the `.ws` files into a `wirespecJar` and add it to Maven publications. Default `false`.
     *
     * Pairs with [jarPath]: [jarEnabled] turns jar packaging on, [jarPath] is the in-jar directory.
     */
    abstract val jarEnabled: Property<Boolean>

    /**
     * Directory inside the generated jar under which the `.ws` files are placed (dot- or
     * slash-separated). Default: the project name (its artifactId). A per-jar prefix keeps
     * `.ws` files from several published jars from colliding by path on one classpath.
     *
     * Pairs with [jarEnabled]; has no effect unless jar packaging is enabled.
     */
    abstract val jarPath: Property<String>
}
