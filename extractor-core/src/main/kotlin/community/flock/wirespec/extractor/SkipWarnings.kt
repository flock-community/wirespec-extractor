package community.flock.wirespec.extractor

/**
 * Text of the warnings logged when a class is skipped, so they say what was skipped, what
 * that costs, and why: the exception type, and where it was thrown, instead of a bare
 * exception message such as `jakarta/ws/rs/core/Response`.
 *
 * The most common cause gets a fix: a class the scanned code references but that is not on
 * the extraction classpath, typically because its jar is a dependency the extraction
 * classpath did not include, or that was excluded.
 */
internal object SkipWarnings {

    private const val OWN_PACKAGE = "community.flock.wirespec."
    private const val NO_ENDPOINTS = "its endpoints are missing from the contract"

    /**
     * "Skipping JAX-RS resource com.acme.FooResource, so its endpoints are missing from the
     * contract: class jakarta.ws.rs.core.Response is not on the extraction classpath (…)".
     */
    fun skipping(kind: String, name: String, t: Throwable, consequence: String = NO_ENDPOINTS): String =
        skipping(kind, name, cause(t), consequence)

    fun skipping(kind: String, name: String, reason: String, consequence: String = NO_ENDPOINTS): String =
        "Skipping $kind $name, so $consequence: $reason"

    /**
     * Why an annotation that is in a class's bytecode is not on the loaded class: Java
     * silently drops an annotation whose class it can't load.
     */
    fun unreadableAnnotation(simpleName: String, className: String): String =
        "its @$simpleName annotation can't be read, because annotation class $className is not on the " +
            "extraction classpath and Java ignores annotations it can't load. " + fix(className)

    /** Why [t] happened, in a sentence a build log reader can act on. */
    fun cause(t: Throwable): String {
        missingClass(t)?.let { (className, error) -> return notOnClasspath(className, error) }
        return describe(t) + rootCause(t)?.let { ", caused by ${describe(it)}" }.orEmpty()
    }

    /** "class X is not on the extraction classpath (error). <how to fix it>" */
    fun notOnClasspath(className: String, error: String? = null): String =
        "class $className is not on the extraction classpath" +
            error?.let { " ($it)" }.orEmpty() + ". " + fix(className)

    /** How to get [className] onto the extraction classpath. */
    fun fix(className: String): String =
        "The extractor loads the compiled classes plus every dependency the module compiles or runs with; " +
            "add the jar that contains $className to the module's dependencies, or to the extractor plugin's."

    /**
     * The class that could not be loaded, in dotted form, and the error that said so — from
     * [t] or any of its causes (ClassGraph, for one, wraps load failures). Null when [t] is
     * not about a missing class.
     */
    private fun missingClass(t: Throwable): Pair<String, String>? =
        causeChain(t).firstNotNullOfOrNull { e ->
            val name = when (e) {
                // "Could not initialize class X": X was found, but its static initializer failed earlier.
                is NoClassDefFoundError -> e.message?.takeUnless { it.startsWith("Could not initialize class") }
                is ClassNotFoundException -> e.message
                is TypeNotPresentException -> e.typeName()
                else -> null
            }
            // The JVM reports internal names (a/b/C), sometimes followed by detail: "a/b/C (wrong name: …)".
            name?.substringBefore(' ')?.takeIf { it.isNotBlank() }
                ?.let { it.replace('/', '.') to e.javaClass.simpleName }
        }

    /** "IllegalStateException: message (at JaxRsResponseScanner.scan(JaxRsResponseScanner.kt:42))" */
    private fun describe(t: Throwable): String {
        val frame = t.stackTrace.firstOrNull { it.className.startsWith(OWN_PACKAGE) } ?: t.stackTrace.firstOrNull()
        return t.javaClass.name +
            t.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() +
            frame?.let { " (at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber}))" }.orEmpty()
    }

    private fun rootCause(t: Throwable): Throwable? = causeChain(t).drop(1).lastOrNull()

    private fun causeChain(t: Throwable): List<Throwable> =
        generateSequence(t) { e -> e.cause?.takeIf { it !== e } }.take(16).toList()
}
