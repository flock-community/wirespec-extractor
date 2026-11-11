package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.ApiResponseExtractor
import community.flock.wirespec.extractor.extract.KotlinNames
import community.flock.wirespec.extractor.extract.PathParser
import community.flock.wirespec.extractor.extract.ReturnTypeUnwrapper
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.annotationNamed
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.stringAttr
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.Param
import io.swagger.v3.oas.annotations.Operation
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/**
 * Extracts Wirespec endpoints from a JAX-RS resource class.
 *
 * Routing (path + HTTP method) is read from JAX-RS annotations (`@Path`,
 * `@GET`/`@POST`/…), on the resource method or inherited from the superclass or
 * interface method it overrides; every other facet — parameters, request body, responses,
 * referenced types, and the operation name — is driven by swagger/OpenAPI
 * annotations. The produced [Endpoint] model is identical to the Spring path's,
 * so it flows through the same AST builder, type ownership, and emitter.
 */
class JaxRsEndpointExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    private val params = JaxRsParamExtractor(types)
    private val apiResponses = ApiResponseExtractor(types, onWarn)

    /**
     * Extract the endpoints of [resourceClass]. [otherResources] are the other root
     * resources of the same scan: a method whose JAX-RS annotations are inherited from one
     * of them is left to that resource, so a `@Path` class implementing a `@Path`
     * interface does not emit every endpoint twice.
     */
    fun extract(resourceClass: Class<*>, otherResources: Collection<Class<*>> = emptyList()): List<Endpoint> {
        val classPath = resourceClass.annotationNamed(JaxRs.PATH)?.stringAttr().orEmpty()
        return resourceClass.methods
            .filterNot { it.isBridge || it.isSynthetic }
            .flatMap { method ->
                val annotated = JaxRs.annotationSource(method) ?: return@flatMap emptyList()
                val inheritedFromOtherResource = annotated != method &&
                    annotated.declaringClass != resourceClass &&
                    annotated.declaringClass in otherResources
                if (inheritedFromOtherResource) emptyList()
                else extractFromMethod(resourceClass, classPath, method, annotated)
            }
    }

    /**
     * @param method    the resource class's own method: the source of the return type and
     *                  the concrete parameter types.
     * @param annotated the method carrying the JAX-RS annotations — [method] itself, or the
     *                  superclass/interface declaration it inherits them from.
     */
    private fun extractFromMethod(
        resourceClass: Class<*>,
        classPath: String,
        method: Method,
        annotated: Method,
    ): List<Endpoint> {
        val httpMethodName = JaxRs.httpMethodOf(annotated) ?: return emptyList()
        val operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation::class.java)
        if (operation?.hidden == true) return emptyList()

        val httpMethod = httpMethodName.toHttpMethod() ?: run {
            onWarn("Skipping unsupported JAX-RS HTTP method '$httpMethodName' on ${resourceClass.simpleName}#${method.name}")
            return emptyList()
        }

        val methodPath = (method.annotationNamed(JaxRs.PATH) ?: annotated.annotationNamed(JaxRs.PATH))
            ?.stringAttr().orEmpty()
        val path = joinPath(classPath, methodPath)
        val pathVariables = PathParser.parse(path).filterIsInstance<PathSegment.Variable>().map { it.name }.toSet()

        val bound = params.bind(method, annotated, pathVariables)
        val pathParamTypes = bound.params.filter { it.source == Param.Source.PATH }.associate { it.name to it.type }
        val unwrapped = ReturnTypeUnwrapper.unwrap(method)
        val responses = apiResponses.extract(method, unwrapped, naturalResponse(method, annotated, unwrapped))

        val name = operation?.operationId?.takeIf { it.isNotBlank() }?.let(::pascalCase)
            ?: pascalCase(KotlinNames.demangle(method.name))

        return listOf(
            Endpoint(
                controllerSimpleName = resourceClass.simpleName,
                name = name,
                method = httpMethod,
                pathSegments = PathParser.parse(path, pathParamTypes),
                queryParams = bound.params.filter { it.source == Param.Source.QUERY },
                headerParams = bound.params.filter { it.source == Param.Source.HEADER },
                cookieParams = bound.params.filter { it.source == Param.Source.COOKIE },
                requestBody = bound.body,
                responses = responses,
            )
        )
    }

    /**
     * The response implied by the method signature where the generic, Spring-oriented
     * reading of it is wrong for JAX-RS, or null to keep that reading:
     *
     * - an untyped `Response` hides its entity, so it is a body-less 200 rather than a
     *   `String` — declare the real shape with `@ApiResponse`;
     * - a Quarkus REST `@ResponseStatus` sets the status.
     */
    private fun naturalResponse(method: Method, annotated: Method, unwrapped: ReturnTypeUnwrapper.Unwrapped): Endpoint.Response? {
        val status = JaxRs.responseStatusOf(method) ?: JaxRs.responseStatusOf(annotated)
        val untyped = unwrapped.type.typeName in JaxRs.RESPONSE
        if (status == null && !untyped) return null
        val body = if (untyped || unwrapped.isVoid) null else apiResponses.methodBody(unwrapped)
        return Endpoint.Response(status ?: 200, body)
    }

    private fun joinPath(a: String, b: String): String {
        val left = a.trim('/').takeIf { it.isNotBlank() }
        val right = b.trim('/').takeIf { it.isNotBlank() }
        return listOfNotNull(left, right).joinToString("/")
    }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)

    private fun String.toHttpMethod(): HttpMethod? = when (uppercase()) {
        "GET" -> HttpMethod.GET
        "POST" -> HttpMethod.POST
        "PUT" -> HttpMethod.PUT
        "PATCH" -> HttpMethod.PATCH
        "DELETE" -> HttpMethod.DELETE
        "OPTIONS" -> HttpMethod.OPTIONS
        "HEAD" -> HttpMethod.HEAD
        "TRACE" -> HttpMethod.TRACE
        else -> null
    }
}
