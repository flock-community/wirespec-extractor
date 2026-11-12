package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.DeclaredResponse
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.WireType
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.annotation.RepeatableContainers
import java.lang.reflect.Method

/**
 * Reads MicroProfile OpenAPI `@APIResponse` / `@APIResponses`
 * (`org.eclipse.microprofile.openapi.annotations.responses`) — the response annotations
 * Quarkus projects use with `quarkus-smallrye-openapi` — as the counterpart of swagger's
 * `@ApiResponse` / `@ApiResponses`.
 *
 * Like the JAX-RS annotations they are read reflectively by fully-qualified name, so the
 * extractor needs no MicroProfile API on its own classpath.
 */
internal object MicroProfileApiResponses {

    private const val RESPONSES = "org.eclipse.microprofile.openapi.annotations.responses"
    private const val API_RESPONSE = "$RESPONSES.APIResponse"
    private const val API_RESPONSES = "$RESPONSES.APIResponses"

    /**
     * The responses declared on [method] or a method it overrides: the `@APIResponses`
     * container's entries first, then standalone `@APIResponse`s.
     */
    fun of(method: Method, types: TypeExtractor): List<DeclaredResponse> {
        val annotations = MergedAnnotations.from(
            method,
            MergedAnnotations.SearchStrategy.TYPE_HIERARCHY,
            RepeatableContainers.none(),
        )
        val contained = annotations.stream<Annotation>(API_RESPONSES).toList()
            .flatMap { container -> (container.synthesize().attr("value") as? Array<*>)?.toList().orEmpty() }
        val standalone = annotations.stream<Annotation>(API_RESPONSE).toList().map { it.synthesize() }
        return (contained + standalone).filterIsInstance<Annotation>().map { response ->
            DeclaredResponse(response.attr("responseCode") as? String ?: "") { bodyOf(response, types) }
        }
    }

    /**
     * The body the first `content` entry's schema declares: its `implementation`, as a list
     * when the schema's `type` is `ARRAY`. Null when no schema names an implementation.
     */
    private fun bodyOf(response: Annotation, types: TypeExtractor): WireType? {
        val content = (response.attr("content") as? Array<*>)?.firstOrNull() as? Annotation ?: return null
        val schema = content.attr("schema") as? Annotation ?: return null
        val implementation = schema.attr("implementation") as? Class<*> ?: return null
        if (implementation == Void::class.java) return null
        val body = types.extract(implementation)
        return if ((schema.attr("type") as? Enum<*>)?.name == "ARRAY") WireType.ListOf(body) else body
    }

    private fun Annotation.attr(name: String): Any? =
        runCatching { annotationClass.java.getMethod(name).invoke(this) }.getOrNull()
}
