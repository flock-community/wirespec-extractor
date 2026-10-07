package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.extract.KotlinNames
import community.flock.wirespec.extractor.extract.NullabilityResolver
import community.flock.wirespec.extractor.extract.ReturnTypeUnwrapper
import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.AnnotationAttributes
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Turns a Spring for GraphQL controller into [Rpc] domain values: one per query,
 * mutation, or subscription handler.
 *
 * Every mapping annotation is read as its merged `@SchemaMapping` — `@QueryMapping` is
 * `@SchemaMapping(typeName = "Query")` and so on — which keeps composed annotations and
 * the class-level `typeName` default working. Mappings on any other type are field
 * resolvers (`@SchemaMapping(typeName = "Book") fun author(book: Book)`), not operations,
 * and are skipped. Annotations are matched by fully-qualified name, so Spring for GraphQL
 * need not be on the extractor's own classpath.
 */
internal class GraphQlRpcExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    fun extract(controller: Class<*>): List<Rpc> {
        val classTypeName = attributes(controller, GraphQlScanner.SCHEMA_MAPPING)?.getString("typeName").orEmpty()
        // Sorted so the emitted order doesn't depend on the JVM's reflection order.
        return controller.declaredMethods
            .filterNot { it.isSynthetic || it.isBridge }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))
            .mapNotNull { extractFromMethod(controller, classTypeName, it) }
    }

    private fun extractFromMethod(controller: Class<*>, classTypeName: String, method: Method): Rpc? {
        val mapping = attributes(method, GraphQlScanner.SCHEMA_MAPPING) ?: return null
        val kind = Rpc.Kind.of(mapping.getString("typeName").ifEmpty { classTypeName }) ?: return null
        return Rpc(
            ownerSimpleName = controller.simpleName,
            field = mapping.getString("field").ifEmpty { KotlinNames.demangle(method.name) },
            kind = kind,
            arguments = method.parameters.indices.flatMap { argumentFields(method, it) },
            result = result(method, kind),
        )
    }

    /**
     * The RPC fields one handler parameter contributes: one for `@Argument`, the bound
     * object's properties for `@Arguments`, none for everything else (the source object,
     * `DataFetchingEnvironment`, `@ContextValue`, `Principal`, a suspend `Continuation`, …).
     */
    private fun argumentFields(method: Method, index: Int): List<WireType.Field> {
        val p = method.parameters[index]
        val where = "${method.declaringClass.name}#${method.name}"
        attributes(p, ARGUMENT)?.let { a ->
            val explicitName = a.getString("name")
            if (explicitName.isEmpty() && isMap(p.type)) {
                onWarn("graphql: skipping raw argument map '${p.name}' on $where")
                return emptyList()
            }
            var type: Type = p.parameterizedType
            var nullable = NullabilityResolver.isParameterNullable(p, springOptional = false)
            // ArgumentValue<T> distinguishes "omitted" from "null" — either way the argument may be absent.
            if (rawClass(type)?.name == ARGUMENT_VALUE) {
                type = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: Any::class.java
                nullable = true
            }
            return listOf(WireType.Field(explicitName.ifEmpty { p.name }, types.extract(type, nullable)))
        }
        if (attributes(p, ARGUMENTS) != null) {
            val cls = rawClass(p.parameterizedType)
            if (cls == null || isMap(cls)) {
                onWarn("graphql: skipping @Arguments '${p.name}' on $where: not an object type")
                return emptyList()
            }
            return types.fieldsOf(cls)
        }
        return emptyList()
    }

    /**
     * The operation's result. Single-value wrappers (`Mono`, `CompletableFuture`, …) are
     * unwrapped; a stream (`Flux`, `Publisher`, `Flow`) is a list for a query or mutation
     * but the event stream itself for a subscription, whose result is one event.
     */
    private fun result(method: Method, kind: Rpc.Kind): WireType? {
        var current = ReturnTypeUnwrapper.effectiveReturnType(method)
        var isList = false
        var streamPending = kind == Rpc.Kind.SUBSCRIPTION
        while (current is ParameterizedType) {
            val rawName = (current.rawType as? Class<*>)?.name ?: break
            when (rawName) {
                in STREAMS -> if (streamPending) streamPending = false else isList = true
                in WRAPPERS -> {}
                else -> break
            }
            current = current.actualTypeArguments[0]
        }
        if (isVoid(current)) return null
        val nullable = NullabilityResolver.isReturnNullable(method)
        return if (isList) WireType.ListOf(types.extract(current), nullable) else types.extract(current, nullable)
    }

    private fun attributes(element: AnnotatedElement, annotationName: String): AnnotationAttributes? =
        AnnotatedElementUtils.findMergedAnnotationAttributes(element, annotationName, false, false)

    private fun rawClass(type: Type): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        else -> null
    }

    private fun isMap(cls: Class<*>): Boolean = Map::class.java.isAssignableFrom(cls)

    private fun isVoid(type: Type): Boolean =
        type == Void.TYPE || type == Void::class.java || type.typeName == "kotlin.Unit"

    private companion object {
        const val ARGUMENT = "${GraphQlScanner.PACKAGE}.Argument"
        const val ARGUMENTS = "${GraphQlScanner.PACKAGE}.Arguments"
        const val ARGUMENT_VALUE = "org.springframework.graphql.data.ArgumentValue"

        val WRAPPERS = setOf(
            "reactor.core.publisher.Mono",
            "java.util.Optional",
            "java.util.concurrent.Callable",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.CompletionStage",
            "graphql.execution.DataFetcherResult",
        )
        val STREAMS = setOf(
            "reactor.core.publisher.Flux",
            "org.reactivestreams.Publisher",
            "kotlinx.coroutines.flow.Flow",
        )
    }
}
