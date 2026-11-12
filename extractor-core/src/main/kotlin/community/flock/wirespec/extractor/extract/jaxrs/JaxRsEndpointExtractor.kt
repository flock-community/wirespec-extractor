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
import community.flock.wirespec.extractor.model.WireType
import io.swagger.v3.oas.annotations.Operation
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

/**
 * Extracts Wirespec endpoints from a JAX-RS resource class.
 *
 * Routing (path + HTTP method) is read from JAX-RS annotations (`@Path`,
 * `@GET`/`@POST`/…), on the resource method or inherited from the superclass or
 * interface method it overrides; every other facet — parameters, request body, responses,
 * referenced types, and the operation name — is driven by swagger/OpenAPI
 * annotations, plus the responses the method body builds when it returns a
 * `Response` (see [JaxRsResponseScanner]). The produced [Endpoint] model is identical to the Spring path's,
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
        val responses = apiResponses.extract(method, unwrapped, naturalResponses(resourceClass, method, annotated, unwrapped))

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
     * The responses the method implies where the generic, Spring-oriented reading of its
     * signature is wrong for JAX-RS, or null to keep that reading:
     *
     * 1. a Quarkus REST `@ResponseStatus` sets the status;
     * 2. else the responses the method body builds and returns — `Response.ok(x).build()`,
     *    `Response.status(Status.NOT_FOUND).build()`, `RestResponse.notFound()`, … — one per
     *    status (see [JaxRsResponseScanner]);
     * 3. else an untyped `Response` is a body-less 200 rather than a `String`, with a
     *    warning unless `@ApiResponse` / `@APIResponse` declares the real shape.
     */
    private fun naturalResponses(
        resourceClass: Class<*>,
        method: Method,
        annotated: Method,
        unwrapped: ReturnTypeUnwrapper.Unwrapped,
    ): List<ApiResponseExtractor.Natural>? {
        val untyped = JaxRs.isUntypedResponse(unwrapped.type)

        val status = JaxRs.responseStatusOf(method) ?: JaxRs.responseStatusOf(annotated)
        if (status != null) {
            val body = if (untyped || unwrapped.isVoid) null else apiResponses.methodBody(unwrapped)
            return listOf(ApiResponseExtractor.Natural(status) { body })
        }

        val built = if (returnsBuiltResponse(method)) JaxRsResponseScanner.scan(method) else emptyList()
        if (built.isNotEmpty()) {
            return built.groupBy { it.status }.toSortedMap().map { (status, variants) ->
                ApiResponseExtractor.Natural(status) { bodyOf(variants, untyped, unwrapped) }
            }
        }

        if (!untyped) return null
        if (!apiResponses.declares(method)) {
            onWarn(
                "Could not read the responses of ${resourceClass.simpleName}#${method.name} from its body: " +
                    "it returns an untyped ${(unwrapped.type as Class<*>).simpleName}, so the contract states a " +
                    "body-less 200 and is probably incomplete. Declare the responses with @ApiResponse or @APIResponse."
            )
        }
        return listOf(ApiResponseExtractor.Natural(200) { null })
    }

    /** Whether [method] returns a `Response` / `RestResponse` directly, as built in its body. */
    private fun returnsBuiltResponse(method: Method): Boolean {
        val type = ReturnTypeUnwrapper.effectiveReturnType(method)
        val raw = (type as? ParameterizedType)?.rawType ?: type
        return raw is Class<*> && raw.name in JaxRs.BUILT_RESPONSES
    }

    /**
     * The body of the responses of one status built in the method body. A typed
     * `RestResponse<T>` can only carry a `T`, so its signature states the type; an untyped
     * one carries what was passed as the entity. Entities of different types, or of a type
     * the bytecode does not reveal, are `Any`.
     */
    private fun bodyOf(
        variants: List<JaxRsResponseScanner.Built>,
        untyped: Boolean,
        unwrapped: ReturnTypeUnwrapper.Unwrapped,
    ): WireType? {
        val bodies = variants.mapNotNull { built ->
            when (val entity = built.entity) {
                JaxRsResponseScanner.Entity.None -> null
                else -> if (!untyped) apiResponses.methodBody(unwrapped) else entityBody(entity)
            }
        }.distinct()
        return when (bodies.size) {
            0 -> null
            1 -> bodies.single()
            else -> WireType.Any()
        }
    }

    private fun entityBody(entity: JaxRsResponseScanner.Entity): WireType = when (entity) {
        is JaxRsResponseScanner.Entity.Of -> types.extract(entity.type)
        else -> WireType.Any()
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
