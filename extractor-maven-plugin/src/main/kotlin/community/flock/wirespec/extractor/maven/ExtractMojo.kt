package community.flock.wirespec.extractor.maven

import community.flock.wirespec.extractor.ExtractConfig
import community.flock.wirespec.extractor.WirespecExtractor
import community.flock.wirespec.extractor.WirespecExtractorException
import community.flock.wirespec.extractor.jar.WirespecJarPackager
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.apache.maven.project.MavenProjectHelper
import java.io.File

@Mojo(
    name = "extract",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true,
)
class ExtractMojo : AbstractMojo() {

    @Parameter(property = "wirespec.output", defaultValue = "\${project.build.directory}/wirespec")
    lateinit var output: File

    @Parameter(property = "wirespec.basePackage")
    var basePackage: String? = null

    /** Extract Spring MVC controllers, functional-DSL routes, and messaging channels. */
    @Parameter(property = "wirespec.extractSpring", defaultValue = "true")
    var extractSpring: Boolean = true

    /** Extract JAX-RS resources whose OpenAPI detail is driven by swagger annotations. */
    @Parameter(property = "wirespec.extractOpenApi", defaultValue = "true")
    var extractOpenApi: Boolean = true

    /** Extract Ktor server routing trees and Ktor client request calls. */
    @Parameter(property = "wirespec.extractKtor", defaultValue = "true")
    var extractKtor: Boolean = true

    /**
     * When `true`, bundle the emitted `.ws` files into a jar and attach it to the
     * project under the [jarClassifier] classifier, so `mvn install`/`deploy`
     * publishes it alongside the main artifact. Default `false`.
     */
    @Parameter(property = "wirespec.generateJar", defaultValue = "false")
    var generateJar: Boolean = false

    /** Classifier for the attached Wirespec jar. Default `wirespec`. */
    @Parameter(property = "wirespec.jarClassifier", defaultValue = "wirespec")
    var jarClassifier: String = "wirespec"

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var project: MavenProject

    /** Injected by Maven; attaches the generated jar as a secondary artifact. */
    @Component
    private var projectHelper: MavenProjectHelper? = null

    override fun execute() {
        val runtimeClasspath: List<File> = try {
            project.runtimeClasspathElements.orEmpty().map(::File)
        } catch (_: Exception) {
            // DependencyResolutionRequiredException in unit-test contexts where
            // dependency resolution hasn't run; tolerate it and use empty classpath.
            emptyList()
        }

        try {
            WirespecExtractor.extract(
                ExtractConfig(
                    classesDirectories = listOf(File(project.build.outputDirectory)),
                    runtimeClasspath = runtimeClasspath,
                    outputDirectory = output,
                    basePackage = basePackage,
                    extractSpring = extractSpring,
                    extractOpenApi = extractOpenApi,
                    extractKtor = extractKtor,
                    log = MavenExtractLog(log),
                )
            )
        } catch (e: WirespecExtractorException) {
            throw MojoExecutionException(e.message, e.cause)
        }

        if (generateJar) {
            val packageDir = WirespecJarPackager.packagePath(basePackage, fallback = project.groupId)
            val jarFile = File(project.build.directory, "${project.build.finalName}-$jarClassifier.jar")
            WirespecJarPackager.pack(output, packageDir, jarFile)
            projectHelper?.attachArtifact(project, "jar", jarClassifier, jarFile)
                ?: log.warn("MavenProjectHelper unavailable; built ${jarFile.name} but did not attach it.")
            log.info("Wirespec jar: ${jarFile.name} (classifier '$jarClassifier', package '$packageDir')")
        }
    }
}
