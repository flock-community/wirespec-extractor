package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.NullabilityResolver
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.annotationNamed
import community.flock.wirespec.extractor.extract.jaxrs.JaxRs.hasAnnotation
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.Param.Source
import community.flock.wirespec.extractor.model.WireType
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.parameters.RequestBody
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import io.swagger.v3.oas.annotations.Parameter as SwaggerParameter

/**
 * Extracts query/header/cookie/path parameters and the request entity body
 * from a JAX-RS resource method.
 *
 * Binding source and name come from JAX-RS annotations (`@PathParam`,
 * `@QueryParam`, `@HeaderParam`, `@CookieParam`) or their Quarkus REST
 * shorthands (`@RestPath`, `@RestQuery`, `@RestHeader`, `@RestCookie`) — or,
 * where a parameter is declared only with swagger's `@Parameter(in = …)`, from
 * that. All OpenAPI detail (required flag, body schema) is taken from the
 * swagger annotations.
 */
class JaxRsParamExtractor(private val types: TypeExtractor) {

    /** The parameters and request entity of one resource method. */
    data class Bound(val params: List<Param>, val body: WireType?)

    /**
     * One method argument. [annotated] carries the JAX-RS annotations and the name;
     * [typed] carries the type. They are the same parameter unless the annotations are
     * inherited from an overridden method, where the override has the concrete types.
     */
    private data class Arg(val annotated: Parameter, val typed: Parameter) {
        val name: String get() = if (annotated.isNamePresent) annotated.name else typed.name
    }

    /**
     * Bind the arguments of [method], reading annotations from [annotated] (the method
     * itself, or the overridden declaration its JAX-RS annotations are inherited from).
     * [pathVariables] are the `{names}` of the full path template: under Quarkus REST a
     * bare argument named after one that no other argument binds is that path parameter.
     */
    fun bind(method: Method, annotated: Method, pathVariables: Set<String>): Bound {
        val args = annotated.parameters.zip(method.parameters) { a, t -> Arg(a, t) }
        val explicit = args.associateWith(::explicitParam)

        val implicitPath: Map<Arg, Param> =
            if (JaxRs.isQuarkusRest(annotated.declaringClass.classLoader)) {
                val bound = explicit.values.filterNotNull().filter { it.source == Source.PATH }.map { it.name }
                args.filter { explicit[it] == null && isBare(it) && it.name in pathVariables && it.name !in bound }
                    .associateWith { param(it.name, Source.PATH, it, requiredByDefault = true) }
            } else emptyMap()

        val body = args.firstOrNull { it !in implicitPath && isEntityBody(it) }?.let(::entity)
        return Bound(args.mapNotNull { explicit[it] ?: implicitPath[it] }, body)
    }

    private fun explicitParam(arg: Arg): Param? {
        val p = arg.annotated
        p.annotationNamed(JaxRs.PATH_PARAM)?.let { a ->
            return param(JaxRs.boundName(a, arg.name), Source.PATH, arg, requiredByDefault = true)
        }
        p.annotationNamed(JaxRs.QUERY_PARAM)?.let { a ->
            return param(JaxRs.boundName(a, arg.name), Source.QUERY, arg, requiredByDefault = false)
        }
        p.annotationNamed(JaxRs.HEADER_PARAM)?.let { a ->
            return param(JaxRs.boundName(a, arg.name), Source.HEADER, arg, requiredByDefault = false)
        }
        p.annotationNamed(JaxRs.COOKIE_PARAM)?.let { a ->
            return param(JaxRs.boundName(a, arg.name), Source.COOKIE, arg, requiredByDefault = false)
        }
        // Declared via swagger only (no JAX-RS binding annotation): honour @Parameter(in = …).
        swaggerParam(p)?.let { sp ->
            val source = sp.`in`.toSource() ?: return null
            val name = sp.name.ifEmpty { arg.name }
            return param(name, source, arg, requiredByDefault = source == Source.PATH)
        }
        return null
    }

    private fun param(name: String, source: Source, arg: Arg, requiredByDefault: Boolean): Param {
        val required = swaggerParam(arg.annotated)?.required ?: requiredByDefault
        val nullable = NullabilityResolver.isParameterNullable(arg.typed, springOptional = !required)
        return Param(name = name, source = source, type = types.extract(arg.typed.parameterizedType, nullable))
    }

    /**
     * The request entity body type: prefers an explicit swagger `@RequestBody` content
     * schema, else the argument's own type.
     */
    private fun entity(arg: Arg): WireType {
        val swaggerBody = AnnotatedElementUtils.findMergedAnnotation(arg.annotated, RequestBody::class.java)
        SwaggerContent.bodyFromRequestBody(swaggerBody, types)?.let { return it }
        val required = swaggerBody?.required ?: true
        val nullable = NullabilityResolver.isParameterNullable(arg.typed, springOptional = !required)
        return types.extract(arg.typed.parameterizedType, nullable)
    }

    private fun swaggerParam(p: Parameter): SwaggerParameter? =
        AnnotatedElementUtils.findMergedAnnotation(p, SwaggerParameter::class.java)

    /**
     * An argument the framework neither binds nor injects: no JAX-RS binding/injection
     * annotation, no swagger `@Parameter`, and not a container-injected type.
     */
    private fun isBare(arg: Arg): Boolean =
        !arg.annotated.hasAnnotation(JaxRs.INJECTED) &&
            swaggerParam(arg.annotated) == null &&
            !JaxRs.isContextType(arg.typed)

    /**
     * An argument is the request entity when it carries swagger's `@RequestBody`, or when
     * it is [bare][isBare] (a plain, unannotated JAX-RS method argument is the entity body).
     */
    private fun isEntityBody(arg: Arg): Boolean =
        AnnotatedElementUtils.isAnnotated(arg.annotated, RequestBody::class.java.name) || isBare(arg)

    private fun ParameterIn.toSource(): Source? = when (this) {
        ParameterIn.PATH -> Source.PATH
        ParameterIn.QUERY -> Source.QUERY
        ParameterIn.HEADER -> Source.HEADER
        ParameterIn.COOKIE -> Source.COOKIE
        ParameterIn.DEFAULT -> Source.QUERY
    }
}
