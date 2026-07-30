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

class ParamExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    fun extractParams(method: Method): List<Param> =
        method.parameters.withIndex().flatMap { (index, p) -> toParams(method, index, p) }

    fun extractRequestBody(method: Method): WireType? {
        method.parameters.forEachIndexed { index, p ->
            findMergedParameterAnnotation(method, index, RequestBody::class.java)?.let { a ->
                val nullable = NullabilityResolver.isParameterNullable(p, springOptional = !a.required)
                return types.extract(p.parameterizedType, nullable)
            }
        }
        return null
    }

    private fun toParams(method: Method, index: Int, p: Parameter): List<Param> {
        // Only extract the parameter's type once we've confirmed it's actually a Spring
        // binding parameter — otherwise we'd pollute TypeExtractor.definitions with
        // synthetic / framework parameters (notably Kotlin's `Continuation<? super T>`,
        // which would otherwise leak Continuation and CoroutineContext into the schema).
        findMergedParameterAnnotation(method, index, PathVariable::class.java)?.let { a ->
            // Path variables are part of the URL: Spring treats them as required.
            return listOf(param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.PATH, p, springOptional = !a.required))
        }
        findMergedParameterAnnotation(method, index, RequestParam::class.java)?.let { a ->
            return listOf(param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.QUERY, p, springOptional = a.isOptional()))
        }
        findMergedParameterAnnotation(method, index, RequestHeader::class.java)?.let { a ->
            return listOf(param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.HEADER, p, springOptional = a.isOptional()))
        }
        findMergedParameterAnnotation(method, index, CookieValue::class.java)?.let { a ->
            return listOf(param(a.value.ifEmpty { a.name }.ifEmpty { p.name }, Source.COOKIE, p, springOptional = a.isOptional()))
        }
        if (isParameterObject(method, index)) return flattenParameterObject(method, p)
        return emptyList()
    }

    /**
     * springdoc's `@ParameterObject` marks a POJO whose properties Spring binds from
     * individual query parameters (plain `@ModelAttribute`-style binding; the annotation
     * itself only drives documentation). Flatten its bindable fields into QUERY params.
     * The annotation is matched by FQN so springdoc is not a dependency of the extractor;
     * projects without springdoc simply never hit it.
     */
    private fun isParameterObject(method: Method, index: Int): Boolean =
        parameterDeclarations(method, index).any { declaration ->
            declaration.annotations.any { it.annotationClass.java.name in PARAMETER_OBJECT_ANNOTATIONS }
        }

    private fun flattenParameterObject(method: Method, p: Parameter): List<Param> =
        types.flattenQueryFields(p.type) { field ->
            onWarn(
                "${method.declaringClass.simpleName}.${method.name}: dropped @ParameterObject field " +
                    "'${p.type.simpleName}.$field' — its type does not bind as a single query parameter, " +
                    "and Wirespec cannot express nested ('$field.…') query names",
            )
        }.map { f -> Param(name = f.name, source = Source.QUERY, type = f.type) }

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

    private companion object {
        val PARAMETER_OBJECT_ANNOTATIONS = setOf(
            "org.springdoc.core.annotations.ParameterObject", // springdoc 2.x
            "org.springdoc.api.annotations.ParameterObject", // springdoc 1.x
        )
    }
}
