// src/main/kotlin/community/flock/wirespec/extractor/extract/ParamExtractor.kt
package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.Param.Source
import community.flock.wirespec.extractor.model.WireType
import org.springframework.core.ResolvableType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ValueConstants
import java.lang.reflect.Method
import java.lang.reflect.Parameter

class ParamExtractor(private val types: TypeExtractor) {

    fun extractParams(method: Method): List<Param> =
        method.parameters.withIndex().mapNotNull { (index, p) -> toParam(method, index, p) }

    fun extractRequestBody(method: Method): WireType? {
        method.parameters.forEachIndexed { index, p ->
            findMergedParameterAnnotation(method, index, RequestBody::class.java)?.let { a ->
                val nullable = NullabilityResolver.isParameterNullable(p, springOptional = !a.required)
                return types.extract(p.parameterizedType, nullable)
            }
        }
        return null
    }

    private fun toParam(method: Method, index: Int, p: Parameter): Param? {
        // Only extract the parameter's type once we've confirmed it's actually a Spring
        // binding parameter — otherwise we'd pollute TypeExtractor.definitions with
        // synthetic / framework parameters (notably Kotlin's `Continuation<? super T>`,
        // which would otherwise leak Continuation and CoroutineContext into the schema).
        findMergedParameterAnnotation(method, index, PathVariable::class.java)?.let { a ->
            // Path variables are part of the URL: Spring treats them as required.
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.PATH, p, springOptional = !a.required)
        }
        findMergedParameterAnnotation(method, index, RequestParam::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.QUERY, p, springOptional = a.isOptional())
        }
        findMergedParameterAnnotation(method, index, RequestHeader::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.HEADER, p, springOptional = a.isOptional())
        }
        findMergedParameterAnnotation(method, index, CookieValue::class.java)?.let { a ->
            return param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.COOKIE, p, springOptional = a.isOptional())
        }
        return null
    }

    /**
     * Merged-annotation lookup on a method parameter that also sees declarations the
     * implementation inherits. Java does not inherit *parameter* annotations, so plain
     * reflection on a controller that implements an annotated API interface shows nothing —
     * but Spring binds those declarations at runtime (`HandlerMethod.getInterfaceParameterAnnotations`),
     * so the extractor must see them too. Mirrors Spring's lookup order: the implementation's
     * own parameter wins, then the same parameter of each method it overrides on the
     * interfaces of its class hierarchy. Parameter annotations on overridden *superclass*
     * methods stay invisible, as they are to Spring.
     */
    private fun <A : Annotation> findMergedParameterAnnotation(method: Method, index: Int, type: Class<A>): A? =
        parameterDeclarations(method, index).firstNotNullOfOrNull {
            AnnotatedElementUtils.findMergedAnnotation(it, type)
        }

    private fun parameterDeclarations(method: Method, index: Int): Sequence<Parameter> = sequence {
        yield(method.parameters[index])
        var clazz: Class<*>? = method.declaringClass
        while (clazz != null) {
            for (ifc in clazz.interfaces) {
                for (candidate in ifc.methods) {
                    if (isOverrideFor(method, candidate)) yield(candidate.parameters[index])
                }
            }
            clazz = clazz.superclass
        }
    }

    /** Whether [method] overrides [candidate], resolving the candidate's generic parameters against the implementation. */
    private fun isOverrideFor(method: Method, candidate: Method): Boolean {
        if (candidate.name != method.name || candidate.parameterCount != method.parameterCount) return false
        val paramTypes = method.parameterTypes
        if (candidate.parameterTypes.contentEquals(paramTypes)) return true
        return paramTypes.indices.all { i ->
            paramTypes[i] == ResolvableType.forMethodParameter(candidate, i, method.declaringClass).resolve()
        }
    }

    private fun param(name: String, source: Source, p: Parameter, springOptional: Boolean): Param {
        val nullable = NullabilityResolver.isParameterNullable(p, springOptional)
        return Param(name = name, source = source, type = types.extract(p.parameterizedType, nullable))
    }

    /** A param is optional to Spring when it's declared `required = false` or carries a `defaultValue`. */
    private fun RequestParam.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
    private fun RequestHeader.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
    private fun CookieValue.isOptional(): Boolean = !required || defaultValue != ValueConstants.DEFAULT_NONE
}
