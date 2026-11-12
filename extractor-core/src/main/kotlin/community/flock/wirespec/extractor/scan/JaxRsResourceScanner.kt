package community.flock.wirespec.extractor.scan

import community.flock.wirespec.extractor.SkipWarnings
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo

/**
 * Scans for JAX-RS root resource classes: classes annotated with `@Path`
 * (either `jakarta.ws.rs.Path` or the legacy `javax.ws.rs.Path`).
 *
 * String-based annotation lookup means this returns an empty list — and the
 * whole JAX-RS extraction path no-ops — for projects that don't use JAX-RS.
 */
object JaxRsResourceScanner {

    private val PATH_ANNOTATIONS = listOf("jakarta.ws.rs.Path", "javax.ws.rs.Path")

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "springfox",
        "io.swagger",
        "org.apache",
    )

    /**
     * @param scanPackages packages to include in the scan; pass empty for "everything reachable".
     * @param basePackage  if non-null, additionally restrict results to classes whose FQN starts with this prefix.
     * @param onWarn       called with a message whenever a class is skipped: it fails to load, or
     *                     its JAX-RS annotations can't be read.
     */
    fun scan(
        classLoader: ClassLoader,
        scanPackages: List<String>,
        basePackage: String?,
        onWarn: (String) -> Unit = {},
    ): List<Class<*>> {
        val graph = ClassGraph()
            .overrideClassLoaders(classLoader)
            .ignoreParentClassLoaders()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()

        val accepted = scanPackages.filter { it.isNotBlank() }
        if (accepted.isNotEmpty()) graph.acceptPackages(*accepted.toTypedArray())

        graph.scan().use { result ->
            return PATH_ANNOTATIONS
                .flatMap { result.getClassesWithAnnotation(it) }
                .distinctBy { it.name }
                // Accept concrete resource classes and resource interfaces (including Kotlin
                // `fun interface`s), which are an idiomatic way to declare JAX-RS resources.
                // Annotation types carrying `@Path` (e.g. custom meta-annotations) are excluded.
                .filter { ci -> !ci.isAnnotation && (ci.isInterface || (ci.isStandardClass && !ci.isAbstract)) }
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .mapNotNull { ci ->
                    val resource = try {
                        ci.loadClass()
                    } catch (t: Throwable) {
                        onWarn(SkipWarnings.skipping("JAX-RS resource", ci.name, t))
                        return@mapNotNull null
                    }
                    val unreadable = unreadablePath(ci, resource)
                    if (unreadable != null) {
                        onWarn(SkipWarnings.skipping("JAX-RS resource", ci.name, SkipWarnings.unreadableAnnotation("Path", unreadable)))
                        null
                    } else {
                        resource
                    }
                }
        }
    }

    /**
     * Java silently drops an annotation whose class it can't load. A resource whose bytecode
     * carries `@Path`, but whose loaded class does not, therefore has a JAX-RS API the class
     * loader can't see: every one of its JAX-RS annotations is invisible, and it would yield
     * no endpoints without saying why. Returns the `@Path` annotation class it can't read, or
     * null when its `@Path` is readable.
     */
    private fun unreadablePath(ci: ClassInfo, resource: Class<*>): String? {
        val inBytecode = ci.annotationInfo.directOnly().map { it.name }.filter { it in PATH_ANNOTATIONS }
        if (inBytecode.isEmpty()) return null
        // Unreadable for another reason: leave that to extraction, which reports its own error.
        val visible = runCatching { resource.declaredAnnotations.map { it.annotationClass.java.name } }
            .getOrElse { return null }
        return inBytecode.firstOrNull { it !in visible }
    }
}
