package community.flock.wirespec.extractor.extract.graphql

import io.github.classgraph.ClassGraph

/**
 * Discovers Spring for GraphQL annotated controllers: classes declaring at least one
 * `@QueryMapping` / `@MutationMapping` / `@SubscriptionMapping` / `@SchemaMapping` method.
 *
 * String-based annotation lookup: returns an empty list when Spring for GraphQL is
 * absent from the scanned classpath.
 */
internal object GraphQlScanner {

    internal const val PACKAGE = "org.springframework.graphql.data.method.annotation"
    internal const val SCHEMA_MAPPING = "$PACKAGE.SchemaMapping"

    private val MAPPING_ANNOTATIONS = listOf(
        "$PACKAGE.QueryMapping",
        "$PACKAGE.MutationMapping",
        "$PACKAGE.SubscriptionMapping",
        SCHEMA_MAPPING,
    )

    private val FRAMEWORK_EXCLUSIONS = listOf(
        "org.springframework",
        "org.springdoc",
        "org.apache",
    )

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
            return MAPPING_ANNOTATIONS
                .flatMap { result.getClassesWithMethodAnnotation(it) }
                .distinctBy { it.name }
                .filter { ci -> FRAMEWORK_EXCLUSIONS.none { ci.name.startsWith("$it.") } }
                .filter { ci -> basePackage == null || ci.name.startsWith("$basePackage.") || ci.name == basePackage }
                .mapNotNull { ci ->
                    try { ci.loadClass() }
                    catch (t: Throwable) {
                        onWarn("graphql: skipping ${ci.name}: ${t.message}")
                        null
                    }
                }
        }
    }
}
