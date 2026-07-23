package community.flock.wirespec.extractor.gradle

import community.flock.wirespec.extractor.jar.WirespecJarPackager
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

/**
 * Gradle entry point for the Wirespec extractor.
 *
 * Apply alongside any JVM source plugin (`java`, `kotlin("jvm")`, etc.):
 *
 * ```kotlin
 * plugins {
 *     kotlin("jvm")
 *     id("community.flock.wirespec.extractor")
 * }
 * wirespecExtractor { basePackage.set("com.acme.api") }
 * ```
 *
 * Wiring:
 *   - Registers `wirespecExtractor { outputDir; basePackage }` extension.
 *   - When [JavaPlugin] is applied (Kotlin-JVM applies it under the hood),
 *     registers the `extractWirespec` task using `sourceSets.main` outputs
 *     and runtime classpath, and makes `assemble` depend on it so
 *     `gradle build` always extracts.
 */
class WirespecExtractorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("wirespecExtractor", WirespecExtractorExtension::class.java).apply {
            outputDir.convention(project.layout.buildDirectory.dir("wirespec"))
            // basePackage left without a convention → null/unset means scan everything.
            extractSpring.convention(true)
            extractOpenApi.convention(true)
            extractKtor.convention(true)
            generateJar.convention(false)
            jarClassifier.convention("wirespec")
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")

            val extractTask = project.tasks.register("extractWirespec", ExtractWirespecTask::class.java) { t ->
                t.group = "wirespec"
                t.description = "Extract Wirespec .ws files from Spring controllers."
                t.classesDirs.from(main.output.classesDirs)
                t.runtimeClasspath.from(main.runtimeClasspath)
                t.outputDirectory.convention(ext.outputDir)
                t.basePackage.convention(ext.basePackage)
                t.extractSpring.convention(ext.extractSpring)
                t.extractOpenApi.convention(ext.extractOpenApi)
                t.extractKtor.convention(ext.extractKtor)
                // Make sure compile runs first — classes producers wire the dependency.
                t.dependsOn(main.output.classesDirs)
            }

            // Auto-wire: every `gradle build`/`gradle assemble` runs the extractor.
            project.tasks.named("assemble") { it.dependsOn(extractTask) }

            val packageDir = project.provider {
                WirespecJarPackager.packagePath(ext.basePackage.orNull, fallback = project.group.toString().ifBlank { project.name })
            }

            val wirespecJar = project.tasks.register("wirespecJar", Jar::class.java) { jar ->
                jar.group = "wirespec"
                jar.description = "Bundle extracted Wirespec .ws files into a jar."
                jar.archiveClassifier.set(ext.jarClassifier)
                jar.from(extractTask.flatMap { it.outputDirectory }) { spec ->
                    spec.include("**/*.ws")
                    spec.into(packageDir)
                }
                jar.isPreserveFileTimestamps = false
                jar.isReproducibleFileOrder = true
            }

            project.afterEvaluate {
                if (ext.generateJar.getOrElse(false)) {
                    project.tasks.named("assemble") { it.dependsOn(wirespecJar) }
                    project.plugins.withType(MavenPublishPlugin::class.java) {
                        project.extensions.getByType(PublishingExtension::class.java)
                            .publications.withType(MavenPublication::class.java)
                            .configureEach { it.artifact(wirespecJar) }
                    }
                }
            }
        }
    }
}
