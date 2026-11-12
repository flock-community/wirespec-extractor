// src/main/kotlin/community/flock/wirespec/extractor/extract/ApiResponseExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.extract.jaxrs.MicroProfileApiResponses
import community.flock.wirespec.extractor.extract.jaxrs.SwaggerContent
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.WireType
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/** A response declared by an annotation: its `responseCode` and the body its content declares. */
internal class DeclaredResponse(val responseCode: String, val body: () -> WireType?)

/**
 * Reads the responses declared on a handler method — springdoc/swagger `@ApiResponses` /
 * `@ApiResponse`, or MicroProfile OpenAPI `@APIResponses` / `@APIResponse` — and produces
 * one [Endpoint.Response] per declared status.
 *
 * Falls back to the responses the handler itself implies — its signature, or what a
 * scanner read from its body — when no such annotations are present.
 */
class ApiResponseExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    /**
     * A response implied by the handler itself rather than declared. Its body is resolved
     * only when used, so one that a declared response replaces registers no types.
     */
    class Natural(val statusCode: Int, body: () -> WireType?) {
        val body: WireType? by lazy(body)
    }

    /**
     * @param natural overrides the responses derived from the method signature — the ones
     *                used when no response is declared, and whose bodies back a declared
     *                response of the same status that has no content of its own.
     */
    fun extract(
        method: Method,
        unwrapped: ReturnTypeUnwrapper.Unwrapped,
        natural: List<Natural>? = null,
    ): List<Endpoint.Response> {
        val declared = collectDeclared(method)
        val naturals = natural?.takeIf { it.isNotEmpty() } ?: run {
            val body = methodBody(unwrapped)
            listOf(Natural(ReturnTypeUnwrapper.statusCodeFor(method, unwrapped)) { body })
        }

        if (declared.isEmpty()) {
            return naturals.map { Endpoint.Response(it.statusCode, it.body) }
        }

        val out = mutableListOf<Endpoint.Response>()
        for (d in declared) {
            val status = d.responseCode.toIntOrNull()
            if (status == null) {
                onWarn(
                    "Skipping non-numeric @ApiResponse.responseCode '${d.responseCode}' " +
                        "on ${method.declaringClass.simpleName}#${method.name}"
                )
                continue
            }
            val body = d.body() ?: naturals.firstOrNull { it.statusCode == status }?.body
            out += Endpoint.Response(status, body)
        }
        // Defensive: if every entry was skipped, fall back to the natural responses so the
        // endpoint still has at least one response.
        return out.ifEmpty { naturals.map { Endpoint.Response(it.statusCode, it.body) } }
    }

    /** Whether [method] declares its responses with `@ApiResponse`(s) or `@APIResponse`(s). */
    fun declares(method: Method): Boolean = collectDeclared(method).isNotEmpty()

    /** Swagger's declarations, then MicroProfile's for statuses swagger does not declare. */
    private fun collectDeclared(method: Method): List<DeclaredResponse> {
        val swagger = collectApiResponses(method).map { ann ->
            DeclaredResponse(ann.responseCode) { bodyFromContent(ann) }
        }
        val seen = swagger.map { it.responseCode }.toMutableSet()
        return swagger + MicroProfileApiResponses.of(method, types).filter { seen.add(it.responseCode) }
    }

    private fun collectApiResponses(method: Method): List<ApiResponse> {
        // Use AnnotatedElementUtils so meta-annotated and inherited variants are found.
        val container = AnnotatedElementUtils.findMergedAnnotation(method, ApiResponses::class.java)
        val containerEntries = container?.value?.toList().orEmpty()
        val standalone = AnnotatedElementUtils.findAllMergedAnnotations(method, ApiResponse::class.java).toList()
        // Container takes precedence; merge any standalone entries that aren't already covered by status code.
        val seen = containerEntries.map { it.responseCode }.toMutableSet()
        val merged = containerEntries.toMutableList()
        for (a in standalone) {
            if (seen.add(a.responseCode)) merged += a
        }
        return merged
    }

    /** The response body the method signature declares, or null for a void method. */
    fun methodBody(unwrapped: ReturnTypeUnwrapper.Unwrapped): WireType? {
        if (unwrapped.isVoid) return null
        val raw = types.extract(unwrapped.type)
        return if (unwrapped.isList) WireType.ListOf(raw) else raw
    }

    /**
     * Reads the first @Content entry's schema. If it carries an `array.schema.implementation`,
     * the body is a list; otherwise `schema.implementation` is used directly. `Void.class`
     * is treated as "no schema declared".
     */
    private fun bodyFromContent(ann: ApiResponse): WireType? =
        SwaggerContent.bodyFromContent(ann.content, types)
}
