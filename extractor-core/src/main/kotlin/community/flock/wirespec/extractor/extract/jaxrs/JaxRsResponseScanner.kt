package community.flock.wirespec.extractor.extract.jaxrs

import community.flock.wirespec.extractor.extract.MethodBodies
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.Value
import org.springframework.http.HttpStatus
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.lang.reflect.Type as JavaType

/**
 * Reads the responses a JAX-RS resource method builds in its body and returns — the
 * status and entity of every `Response` (or Quarkus `RestResponse`) it can return:
 *
 * ```java
 * if (ready) return Response.ok().entity("Ready").build();          // 200, String
 * return Response.status(Response.Status.SERVICE_UNAVAILABLE).build(); // 503, no entity
 * ```
 *
 * The method signature cannot show either: an untyped `Response` hides its entity, and
 * no signature carries a status chosen in the body. This runs a data-flow analysis over
 * the compiled method (ASM's [Analyzer]) that follows builder chains through locals,
 * branches and ternaries, and records the value each `areturn` returns. Recognised:
 *
 * - the static factories of `Response`, `RestResponse` and `RestResponse.ResponseBuilder`
 *   — `ok`, `created`, `accepted`, `noContent`, `seeOther`, `notModified`,
 *   `temporaryRedirect`, `notFound`, `notAcceptable`, `serverError`, `status`/`create`
 *   and `fromResponse`;
 * - on a builder, `status(…)`, `entity(…)` and `build()`; any other call returning the
 *   builder (`header`, `type`, …) leaves it unchanged;
 * - a status given as an `int` constant, a `Response.Status` / `RestResponse.Status`
 *   constant, `Status.X.getStatusCode()` or `Status.fromStatusCode(<int>)`.
 *
 * An entity's type is the static type the bytecode gives its value — a `String` constant,
 * a parameter, a field, the result of a call (with its generic return type), a cast — or,
 * for a JAX-RS `GenericEntity` subclass, its type argument.
 *
 * Anything else is not guessed: a response built by a helper method, inside a lambda (a
 * `Uni<Response>`), or with a status computed at run time is left out, and the caller
 * falls back to the signature.
 */
internal object JaxRsResponseScanner {

    /** A response a resource method returns. */
    data class Built(val status: Int, val entity: Entity)

    /** The entity of a [Built] response. */
    sealed interface Entity {
        /** No entity: an empty body. */
        data object None : Entity

        /** An entity whose static type is [type], with every type argument known. */
        data class Of(val type: JavaType) : Entity

        /**
         * An entity whose type the bytecode does not reveal: values of different types meet,
         * or the type is a raw generic class (`List`, erased from a `List<Fruit>`).
         */
        data object Unknown : Entity
    }

    private val RESPONSES = setOf(
        "jakarta/ws/rs/core/Response",
        "javax/ws/rs/core/Response",
        "org/jboss/resteasy/reactive/RestResponse",
    )
    private val BUILDERS = setOf(
        "jakarta/ws/rs/core/Response\$ResponseBuilder",
        "javax/ws/rs/core/Response\$ResponseBuilder",
        "org/jboss/resteasy/reactive/RestResponse\$ResponseBuilder",
    )
    private val STATUS_ENUMS = setOf(
        "jakarta/ws/rs/core/Response\$Status",
        "javax/ws/rs/core/Response\$Status",
        "org/jboss/resteasy/reactive/RestResponse\$Status",
    )
    private val STATUS_TYPES = STATUS_ENUMS + setOf(
        "jakarta/ws/rs/core/Response\$StatusType",
        "javax/ws/rs/core/Response\$StatusType",
    )
    private val GENERIC_ENTITIES = setOf("jakarta.ws.rs.core.GenericEntity", "javax.ws.rs.core.GenericEntity")

    /** Factories whose name fixes the status. */
    private val FACTORY_STATUS = mapOf(
        "ok" to 200,
        "created" to 201,
        "accepted" to 202,
        "noContent" to 204,
        "seeOther" to 303,
        "notModified" to 304,
        "temporaryRedirect" to 307,
        "notFound" to 404,
        "notAcceptable" to 406,
        "serverError" to 500,
    )

    /** Factories whose first argument, when they take one, is the entity. */
    private val ENTITY_FACTORIES = setOf("ok", "accepted")

    private val OBJECT_TYPE: Type = Type.getType(Any::class.java)

    /**
     * The responses [method] builds and returns, in the order they appear; empty when its
     * body returns none the analysis can follow.
     */
    fun scan(method: Method): List<Built> {
        val loader = method.declaringClass.classLoader ?: return emptyList()
        // A class file ASM cannot read: report nothing rather than fail the build.
        val body = runCatching { MethodBodies.of(method) }.getOrNull() ?: return emptyList()
        val interpreter = ResponseInterpreter(TypeResolver(loader), parameterTypes(method, body))
        val frames = try {
            Analyzer(interpreter).analyze(body.owner, body.node)
        } catch (_: Exception) {
            // Bytecode the analysis cannot follow: report nothing rather than guess.
            return emptyList()
        }

        val found = LinkedHashSet<Built>()
        val instructions = body.node.instructions
        for (i in 0 until instructions.size()) {
            if (instructions[i].opcode != Opcodes.ARETURN) continue
            val frame = frames[i] ?: continue // unreachable
            val returned = frame.getStack(frame.stackSize - 1) as? Responses ?: continue
            for (variant in returned.variants) {
                if (variant.built && variant.status != null) found += Built(variant.status, variant.entity)
            }
        }
        return found.toList()
    }

    /**
     * The generic types of [method]'s parameters by local-variable slot, so an entity passed
     * in as a `List<Fruit>` keeps its element type. Empty when [body] is not [method] itself
     * (a `$suspendImpl`, whose parameters are then read erased from its descriptor).
     */
    private fun parameterTypes(method: Method, body: MethodBodies.Body): Map<Int, JavaType> {
        val node = body.node
        if (node.name != method.name || node.desc != Type.getMethodDescriptor(method)) return emptyMap()
        val erased = Type.getArgumentTypes(node.desc)
        val generic = method.genericParameterTypes
        if (generic.size != erased.size) return emptyMap()
        val out = HashMap<Int, JavaType>()
        var local = if (node.access and Opcodes.ACC_STATIC != 0) 0 else 1
        erased.forEachIndexed { i, type ->
            out[local] = generic[i]
            local += type.size
        }
        return out
    }

    // ---------------------------------------------------------------------------------
    // Abstract values
    // ---------------------------------------------------------------------------------

    /** An abstract value on the operand stack or in a local variable. */
    private sealed interface V : Value

    /** A value the analysis does not track. */
    private data class Other(private val width: Int) : V {
        override fun getSize(): Int = width
    }

    private val OTHER = Other(1)
    private val OTHER_WIDE = Other(2) // long / double

    private data object Null : V {
        override fun getSize(): Int = 1
    }

    private data class IntConst(val value: Int) : V {
        override fun getSize(): Int = 1
    }

    /** A reference whose static type is [type]. */
    private data class Typed(val type: JavaType) : V {
        override fun getSize(): Int = 1
    }

    /** A JAX-RS / Quarkus status constant. */
    private data class StatusConst(val code: Int) : V {
        override fun getSize(): Int = 1
    }

    /**
     * One way a response can be built.
     *
     * @property status null when it is not a constant.
     * @property built  true once `build()` turned the builder into a response.
     */
    private data class Variant(val status: Int?, val entity: Entity, val built: Boolean)

    /** A response or response builder, as every variant it can be on the paths reaching here. */
    private data class Responses(val variants: Set<Variant>) : V {
        override fun getSize(): Int = 1

        fun map(transform: (Variant) -> Variant) = Responses(variants.mapTo(LinkedHashSet(), transform))
    }

    private fun typed(cls: Class<*>?): V = cls?.let(::Typed) ?: OTHER

    // ---------------------------------------------------------------------------------
    // Interpreter
    // ---------------------------------------------------------------------------------

    private class ResponseInterpreter(
        private val types: TypeResolver,
        private val parameterTypes: Map<Int, JavaType>,
    ) : Interpreter<V>(Opcodes.ASM9) {

        override fun newValue(type: Type?): V? = when {
            type == null -> OTHER
            type.sort == Type.VOID -> null
            type.size == 2 -> OTHER_WIDE
            type.sort == Type.OBJECT || type.sort == Type.ARRAY -> typed(types.classOf(type))
            else -> OTHER
        }

        override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): V =
            parameterTypes[local]?.let(::Typed) ?: newValue(type)!!

        override fun newOperation(insn: AbstractInsnNode): V = when (insn.opcode) {
            Opcodes.ACONST_NULL -> Null
            in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> IntConst(insn.opcode - Opcodes.ICONST_0)
            Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> OTHER_WIDE
            Opcodes.BIPUSH, Opcodes.SIPUSH -> IntConst((insn as IntInsnNode).operand)
            Opcodes.LDC -> constant((insn as LdcInsnNode).cst)
            Opcodes.GETSTATIC -> staticField(insn as FieldInsnNode)
            Opcodes.NEW -> typed(types.classOf(Type.getObjectType((insn as TypeInsnNode).desc)))
            else -> OTHER // FCONST_*, JSR
        }

        private fun constant(cst: Any?): V = when (cst) {
            is Int -> IntConst(cst)
            is Long, is Double -> OTHER_WIDE
            is String -> Typed(String::class.java)
            is ConstantDynamic -> if (cst.size == 2) OTHER_WIDE else OTHER
            else -> OTHER
        }

        private fun staticField(insn: FieldInsnNode): V {
            if (insn.owner in STATUS_ENUMS) types.statusCode(insn.owner, insn.name)?.let { return StatusConst(it) }
            return field(insn)
        }

        private fun field(insn: FieldInsnNode): V {
            val type = Type.getType(insn.desc)
            return when {
                type.size == 2 -> OTHER_WIDE
                type.sort == Type.OBJECT || type.sort == Type.ARRAY ->
                    types.fieldType(insn.owner, insn.name)?.let(::Typed) ?: typed(types.classOf(type))
                else -> OTHER
            }
        }

        override fun copyOperation(insn: AbstractInsnNode, value: V): V = value

        override fun unaryOperation(insn: AbstractInsnNode, value: V): V? = when (insn.opcode) {
            Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L,
            Opcodes.F2D, Opcodes.D2L -> OTHER_WIDE
            Opcodes.INEG, Opcodes.FNEG, Opcodes.IINC, Opcodes.I2F, Opcodes.I2B, Opcodes.I2C,
            Opcodes.I2S, Opcodes.L2I, Opcodes.L2F, Opcodes.F2I, Opcodes.D2I, Opcodes.D2F,
            Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF -> OTHER
            Opcodes.GETFIELD -> field(insn as FieldInsnNode)
            Opcodes.CHECKCAST -> cast(value, insn as TypeInsnNode)
            else -> null // branches, switches, returns, PUTSTATIC, ATHROW, MONITOR*
        }

        /** A cast narrows an untracked value to the cast type but never loses what is known. */
        private fun cast(value: V, insn: TypeInsnNode): V {
            val target = types.classOf(Type.getObjectType(insn.desc))
            return when {
                value is Responses || value is Null -> value
                value is Typed && target != null && rawClass(value.type)?.let(target::isAssignableFrom) == true -> value
                else -> typed(target)
            }
        }

        override fun binaryOperation(insn: AbstractInsnNode, value1: V, value2: V): V? = when (insn.opcode) {
            Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.DADD, Opcodes.LSUB, Opcodes.DSUB,
            Opcodes.LMUL, Opcodes.DMUL, Opcodes.LDIV, Opcodes.DDIV, Opcodes.LREM, Opcodes.DREM,
            Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> OTHER_WIDE
            in Opcodes.IF_ICMPEQ..Opcodes.IF_ACMPNE, Opcodes.PUTFIELD -> null
            else -> OTHER
        }

        override fun ternaryOperation(insn: AbstractInsnNode, value1: V, value2: V, value3: V): V? = null

        override fun naryOperation(insn: AbstractInsnNode, values: List<V>): V? = when (insn) {
            is MethodInsnNode -> invoke(insn, values)
            is InvokeDynamicInsnNode -> result(Type.getReturnType(insn.desc)) { null }
            else -> OTHER // MULTIANEWARRAY
        }

        override fun returnOperation(insn: AbstractInsnNode, value: V, expected: V) = Unit

        /**
         * Joins the values two paths bring to the same place. A response stays a response
         * when the other path brings something untracked (a `null`, a helper's result): what
         * is known about it is kept, and only ever grows, so the analysis terminates.
         */
        override fun merge(value1: V, value2: V): V = when {
            value1 == value2 -> value1
            value1 is Responses && value2 is Responses -> Responses(value1.variants + value2.variants)
            value1 is Responses -> value1
            value2 is Responses -> value2
            value1 is Null -> value2
            value2 is Null -> value1
            value1.size == 2 && value2.size == 2 -> OTHER_WIDE
            else -> OTHER
        }

        private fun invoke(insn: MethodInsnNode, values: List<V>): V? {
            val receiver = if (insn.opcode == Opcodes.INVOKESTATIC) null else values.first()
            val args = if (receiver == null) values else values.drop(1)
            responseCall(insn, receiver, args)?.let { return it }
            statusCall(insn, receiver, args)?.let { return it }
            return result(Type.getReturnType(insn.desc)) { types.returnType(insn.owner, insn.name, insn.desc) }
        }

        /** A call creating or changing a response or builder; null for any other call. */
        private fun responseCall(insn: MethodInsnNode, receiver: V?, args: List<V>): V? {
            if (insn.owner !in RESPONSES && insn.owner !in BUILDERS) return null
            val returns = Type.getReturnType(insn.desc).takeIf { it.sort == Type.OBJECT }?.internalName
            val built = when (returns) {
                in RESPONSES -> true
                in BUILDERS -> false
                else -> return null
            }

            if (receiver == null) {
                val variant = when (insn.name) {
                    in FACTORY_STATUS -> Variant(
                        status = FACTORY_STATUS.getValue(insn.name),
                        entity = if (insn.name in ENTITY_FACTORIES && args.isNotEmpty()) entityOf(args[0]) else Entity.None,
                        built = built,
                    )
                    // status(code) / status(code, reasonPhrase) / status(status, entity) / create(…)
                    "status", "create" -> {
                        val params = Type.getArgumentTypes(insn.desc)
                        val entity = if (params.size >= 2 && params[1] == OBJECT_TYPE) entityOf(args[1]) else Entity.None
                        Variant(statusOf(args.firstOrNull()), entity, built)
                    }
                    "fromResponse" -> return (args.firstOrNull() as? Responses)?.map { it.copy(built = built) }
                    else -> return null
                }
                return Responses(setOf(variant))
            }

            if (insn.owner !in BUILDERS) return null
            val builder = receiver as? Responses ?: return null
            return when (insn.name) {
                "status" -> statusOf(args.firstOrNull()).let { status -> builder.map { it.copy(status = status, built = built) } }
                "entity" -> entityOf(args.firstOrNull()).let { entity -> builder.map { it.copy(entity = entity, built = built) } }
                "build" -> builder.map { it.copy(built = true) }
                // header(…), type(…), location(…), clone(), …: the same builder.
                else -> if (built) null else builder
            }
        }

        /** A call reading or creating a status constant; null for any other call. */
        private fun statusCall(insn: MethodInsnNode, receiver: V?, args: List<V>): V? = when {
            insn.name == "getStatusCode" && insn.owner in STATUS_TYPES && receiver is StatusConst ->
                IntConst(receiver.code)
            insn.name == "fromStatusCode" && insn.owner in STATUS_ENUMS && receiver == null ->
                (args.firstOrNull() as? IntConst)?.let { StatusConst(it.value) }
            else -> null
        }

        private fun result(type: Type, generic: () -> JavaType?): V? = when {
            type.sort == Type.VOID -> null
            type.size == 2 -> OTHER_WIDE
            type.sort == Type.OBJECT || type.sort == Type.ARRAY -> generic()?.let(::Typed) ?: typed(types.classOf(type))
            else -> OTHER
        }

        private fun statusOf(value: V?): Int? = when (value) {
            is IntConst -> value.value
            is StatusConst -> value.code
            else -> null
        }?.takeIf { it in 100..599 }

        private fun entityOf(value: V?): Entity = when (value) {
            Null -> Entity.None
            is Typed -> (genericEntityType(value.type) ?: value.type)
                .let { if (isConcrete(it)) Entity.Of(it) else Entity.Unknown }
            else -> Entity.Unknown
        }

        /**
         * The type argument of a JAX-RS `GenericEntity` subclass — `new GenericEntity<List<Fruit>>(fruits) {}`
         * is a `List<Fruit>` — or null when [type] is no such subclass.
         */
        private fun genericEntityType(type: JavaType): JavaType? {
            val cls = type as? Class<*> ?: return null
            val supertype = cls.genericSuperclass as? ParameterizedType ?: return null
            if (supertype.rawType.typeName !in GENERIC_ENTITIES) return null
            return supertype.actualTypeArguments.firstOrNull()
        }
    }

    // ---------------------------------------------------------------------------------
    // Reflection
    // ---------------------------------------------------------------------------------

    /**
     * Resolves the types named in the bytecode against the application's class loader, so a
     * value's type can carry the generic arguments its declaration has.
     */
    private class TypeResolver(private val loader: ClassLoader) {

        private val classes = HashMap<String, Class<*>?>()
        private val returnTypes = HashMap<String, JavaType?>()
        private val fieldTypes = HashMap<String, JavaType?>()

        fun classOf(type: Type): Class<*>? {
            val name = when (type.sort) {
                Type.OBJECT -> type.className
                Type.ARRAY -> type.descriptor.replace('/', '.')
                else -> return null
            }
            return classes.memo(name) { runCatching { Class.forName(name, false, loader) }.getOrNull() }
        }

        /** The generic return type of the method [owner].[name][desc], or null when not fully resolved. */
        fun returnType(owner: String, name: String, desc: String): JavaType? = returnTypes.memo("$owner.$name$desc") {
            val cls = classOf(Type.getObjectType(owner)) ?: return@memo null
            val method = hierarchy(cls).firstNotNullOfOrNull { type ->
                runCatching { type.declaredMethods }.getOrNull()
                    ?.firstOrNull { it.name == name && Type.getMethodDescriptor(it) == desc }
            }
            method?.genericReturnType?.takeIf(::isResolved)
        }

        /** The generic type of the field [owner].[name], or null when not fully resolved. */
        fun fieldType(owner: String, name: String): JavaType? = fieldTypes.memo("$owner.$name") {
            val cls = classOf(Type.getObjectType(owner)) ?: return@memo null
            val field: Field? = hierarchy(cls).firstNotNullOfOrNull { type ->
                runCatching { type.getDeclaredField(name) }.getOrNull()
            }
            field?.genericType?.takeIf(::isResolved)
        }

        /**
         * The status code of the constant [name] of the status enum [owner] — read from the
         * application's own copy of the enum, else matched by name against the standard codes.
         */
        fun statusCode(owner: String, name: String): Int? {
            classOf(Type.getObjectType(owner))?.let { cls ->
                runCatching {
                    val constant = cls.enumConstants.firstOrNull { (it as Enum<*>).name == name }
                    cls.getMethod("getStatusCode").invoke(constant) as? Int
                }.getOrNull()?.let { return it }
            }
            return runCatching { HttpStatus.valueOf(name).value() }.getOrNull()
        }

        /** [getOrPut] that also remembers a null result. */
        private inline fun <T> HashMap<String, T?>.memo(key: String, compute: () -> T?): T? =
            if (containsKey(key)) get(key) else compute().also { put(key, it) }

        /** [cls], its superclasses, then its interfaces. */
        private fun hierarchy(cls: Class<*>): Sequence<Class<*>> = sequence {
            val seen = HashSet<Class<*>>()
            val queue = ArrayDeque(listOf(cls))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next)) continue
                yield(next)
                next.superclass?.let(queue::addLast)
                queue.addAll(next.interfaces)
            }
        }
    }

    /** Whether [type] names no type variable, so it means the same at every call site. */
    private fun isResolved(type: JavaType): Boolean = when (type) {
        is Class<*> -> true
        is ParameterizedType -> type.actualTypeArguments.all(::isResolved)
        is GenericArrayType -> isResolved(type.genericComponentType)
        is WildcardType -> type.upperBounds.all(::isResolved) && type.lowerBounds.all(::isResolved)
        is TypeVariable<*> -> false
        else -> false
    }

    /**
     * Whether [type] can stand for an entity on its own. The bytecode erases generics, so a
     * value can be typed as a raw generic class (`List`, `Page`) — that names no element type.
     */
    private fun isConcrete(type: JavaType): Boolean = when (type) {
        is Class<*> -> if (type.isArray) isConcrete(type.componentType) else type.typeParameters.isEmpty()
        is ParameterizedType -> type.actualTypeArguments.all(::isConcrete)
        is GenericArrayType -> isConcrete(type.genericComponentType)
        is WildcardType -> type.upperBounds.all(::isConcrete)
        else -> false
    }

    private fun rawClass(type: JavaType): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        else -> null
    }
}
