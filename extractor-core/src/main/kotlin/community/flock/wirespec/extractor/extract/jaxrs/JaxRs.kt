package community.flock.wirespec.extractor.extract.jaxrs

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.Type

/**
 * Reflective access to JAX-RS annotations.
 *
 * JAX-RS types are read by fully-qualified name rather than against compiled
 * annotation classes so the extractor:
 *
 * 1. supports both the `jakarta.ws.rs` (Jakarta EE 9+) and the legacy
 *    `javax.ws.rs` namespaces from a single code path, and
 * 2. carries no compile-time dependency on a JAX-RS API on its main classpath —
 *    extraction simply finds nothing (and no-ops) in projects that don't use it.
 *
 * Mirrors the string-based lookup philosophy already used by the messaging
 * scanners.
 *
 * The Quarkus REST (RESTEasy Reactive) shorthand annotations — `@RestPath`,
 * `@RestQuery`, `@RestHeader`, `@RestCookie`, … — are read the same way and
 * treated as their JAX-RS equivalents.
 */
internal object JaxRs {

    private const val QUARKUS_REST = "org.jboss.resteasy.reactive"

    val PATH = ns("Path")
    val PATH_PARAM = ns("PathParam") + rest("RestPath")
    val QUERY_PARAM = ns("QueryParam") + rest("RestQuery")
    val HEADER_PARAM = ns("HeaderParam") + rest("RestHeader")
    val COOKIE_PARAM = ns("CookieParam") + rest("RestCookie")
    val MATRIX_PARAM = ns("MatrixParam") + rest("RestMatrix")
    val FORM_PARAM = ns("FormParam") + rest("RestForm")
    val BEAN_PARAM = ns("BeanParam")
    val CONTEXT = ns("core.Context")
    val SUSPENDED = ns("container.Suspended")
    private val HTTP_METHOD = ns("HttpMethod")

    /** The untyped JAX-RS response: its entity type is not visible in the method signature. */
    val RESPONSE = ns("core.Response")

    /** The Quarkus REST response. Typed, it is unwrapped to its entity; raw, it is as untyped as [RESPONSE]. */
    private const val REST_RESPONSE = "$QUARKUS_REST.RestResponse"

    /** Responses whose status and entity a resource method sets by building them in its body. */
    val BUILT_RESPONSES: List<String> = RESPONSE + REST_RESPONSE

    /** True when [type] — a method's unwrapped return type — hides the entity's type. */
    fun isUntypedResponse(type: Type): Boolean = type is Class<*> && type.name in BUILT_RESPONSES

    private const val REST_HEADER = "$QUARKUS_REST.RestHeader"
    private const val REST_RESPONSE_STATUS = "$QUARKUS_REST.ResponseStatus"

    /**
     * Types the container injects into a resource method. JAX-RS requires `@Context` on
     * them, but Quarkus REST injects them into a bare parameter, so they are recognised by
     * type: such a parameter is never the request entity.
     */
    private val CONTEXT_TYPES: Set<String> = (
        listOf(
            "core.UriInfo", "core.HttpHeaders", "core.Request", "core.SecurityContext",
            "core.Application", "core.Configuration", "container.ResourceInfo",
            "container.ResourceContext", "container.AsyncResponse", "ext.Providers",
            "sse.Sse", "sse.SseEventSink",
        ).flatMap(::ns) + listOf(
            "io.vertx.core.http.HttpServerRequest",
            "io.vertx.core.http.HttpServerResponse",
            "io.vertx.ext.web.RoutingContext",
            "org.jboss.resteasy.reactive.server.SimpleResourceInfo",
            "org.jboss.resteasy.reactive.server.core.CurrentRequest",
            "kotlin.coroutines.Continuation",
        )
    ).toSet()

    /** Every JAX-RS annotation that binds a method parameter to a non-entity source. */
    val PARAM_BINDINGS: List<String> =
        PATH_PARAM + QUERY_PARAM + HEADER_PARAM + COOKIE_PARAM + MATRIX_PARAM + FORM_PARAM

    /** Annotations marking a parameter as framework-injected rather than the request entity. */
    val INJECTED: List<String> = PARAM_BINDINGS + BEAN_PARAM + CONTEXT + SUSPENDED

    private fun ns(simpleName: String): List<String> =
        listOf("jakarta.ws.rs.$simpleName", "javax.ws.rs.$simpleName")

    private fun rest(simpleName: String): List<String> = listOf("$QUARKUS_REST.$simpleName")

    /** True when [p]'s type is one the container injects (see [CONTEXT_TYPES]). */
    fun isContextType(p: Parameter): Boolean = p.type.name in CONTEXT_TYPES

    /**
     * True when Quarkus REST is on [loader]'s classpath, which switches on its extensions to
     * plain JAX-RS binding — notably a bare parameter named after a path variable being a
     * path parameter rather than the request entity.
     */
    fun isQuarkusRest(loader: ClassLoader?): Boolean =
        try {
            Class.forName("$QUARKUS_REST.RestPath", false, loader)
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * The name bound by a parameter annotation: its `value`, else the parameter name.
     * Quarkus derives an unnamed `@RestHeader`'s header from the parameter name
     * (`xTraceId` -> `X-Trace-Id`), so that is mirrored here.
     */
    fun boundName(annotation: Annotation, parameterName: String): String =
        annotation.stringAttr()
            ?: if (annotation.annotationClass.java.name == REST_HEADER) hyphenateCapitalized(parameterName)
            else parameterName

    private val CAMEL_HUMP = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

    private fun hyphenateCapitalized(name: String): String =
        name.split(CAMEL_HUMP).joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) }

    /** The status of a Quarkus REST `@ResponseStatus` on [method], or null when absent. */
    fun responseStatusOf(method: Method): Int? =
        method.annotations.firstOrNull { it.annotationClass.java.name == REST_RESPONSE_STATUS }?.let { a ->
            try {
                a.annotationClass.java.getMethod("value").invoke(a) as? Int
            } catch (_: Exception) {
                null
            }
        }

    /** First annotation on [this] whose type name is one of [names], or null. */
    fun AnnotatedElement.annotationNamed(names: List<String>): Annotation? =
        annotations.firstOrNull { it.annotationClass.java.name in names }

    fun AnnotatedElement.hasAnnotation(names: List<String>): Boolean =
        annotations.any { it.annotationClass.java.name in names }

    /** Read a String-valued attribute (defaults to `value`) off an annotation reflectively. */
    fun Annotation.stringAttr(attr: String = "value"): String? =
        try {
            (annotationClass.java.getMethod(attr).invoke(this) as? String)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }

    /**
     * The HTTP method name (`GET`, `POST`, …) of a resource [method], discovered
     * via the JAX-RS `@HttpMethod` meta-annotation that marks `@GET`/`@POST`/…
     * (and any custom HTTP-method annotation). Returns null when [method] carries
     * no HTTP-method annotation — i.e. it is not a resource method.
     */
    fun httpMethodOf(method: AnnotatedElement): String? {
        for (a in method.annotations) {
            val meta = a.annotationClass.java.annotations.firstOrNull {
                it.annotationClass.java.name in HTTP_METHOD
            }
            if (meta != null) return meta.stringAttr()
        }
        return null
    }

    /**
     * The method whose JAX-RS annotations describe [method]: [method] itself when it carries
     * an HTTP-method annotation, else the nearest superclass or interface method it overrides
     * that does (JAX-RS annotation inheritance — superclasses take precedence over
     * interfaces). Null when [method] is not a resource method.
     */
    fun annotationSource(method: Method): Method? {
        if (httpMethodOf(method) != null) return method
        return supertypesOf(method.declaringClass)
            .flatMap { type -> type.declaredMethods.asSequence().filter { overrides(method, it) } }
            .firstOrNull { httpMethodOf(it) != null }
    }

    /** Every supertype of [type], superclass chain first, then interfaces breadth-first. */
    private fun supertypesOf(type: Class<*>): Sequence<Class<*>> = sequence {
        val superclasses = generateSequence(type.superclass) { it.superclass }.toList()
        yieldAll(superclasses)
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque((listOf(type) + superclasses).flatMap { it.interfaces.asList() })
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            yield(next)
            queue.addAll(next.interfaces)
        }
    }

    /**
     * Whether [method] overrides [candidate]. Parameter types are compared by assignability
     * rather than equality so an implementation of a generic contract still matches its
     * erased declaration (`get(Fruit)` overriding `get(T)`).
     */
    private fun overrides(method: Method, candidate: Method): Boolean =
        candidate.name == method.name &&
            candidate.parameterCount == method.parameterCount &&
            candidate.parameterTypes.zip(method.parameterTypes).all { (declared, actual) ->
                declared.isAssignableFrom(actual)
            }
}
