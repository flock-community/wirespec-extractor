package community.flock.wirespec.extractor.extract

import community.flock.wirespec.extractor.model.DefaultValue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor

/**
 * Recovers the literal default values of a Kotlin class's primary-constructor parameters
 * (`data class Page(val size: Int = 20)`), keyed by parameter name.
 *
 * Kotlin compiles defaults into a synthetic constructor taking the declared parameters
 * plus one `int` bit mask per 32 parameters and a `DefaultConstructorMarker`. For each
 * parameter with a default it emits
 *
 * ```
 * ILOAD mask; <bit>; IAND; IFEQ skip
 * <compute default>; xSTORE param
 * skip:
 * ```
 *
 * A default is reported only when it is computed by a single constant — a literal, an
 * enum entry, optionally boxed — so `= listOf()` or `= Instant.now()` are left out
 * rather than guessed at. `= null` is left out too: a nullable field already says it
 * may be absent.
 */
internal object KotlinDefaults {

    private const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin/jvm/internal/DefaultConstructorMarker"

    private val cache = mutableMapOf<Class<*>, Map<String, DefaultValue>>()

    fun of(cls: Class<*>): Map<String, DefaultValue> = synchronized(cache) {
        cache.getOrPut(cls) {
            try { read(cls) } catch (_: Throwable) { emptyMap() }
        }
    }

    private fun read(cls: Class<*>): Map<String, DefaultValue> {
        if (!cls.isAnnotationPresent(Metadata::class.java)) return emptyMap()
        val primary = cls.kotlin.primaryConstructor ?: return emptyMap()
        val javaParams = primary.javaConstructor?.parameterTypes ?: return emptyMap()
        val names = primary.parameters.map { it.name }
        if (names.size != javaParams.size || names.any { it == null }) return emptyMap()

        val node = classNode(cls) ?: return emptyMap()
        val declared = javaParams.map { Type.getType(it) }
        val maskCount = (declared.size + 31) / 32
        val expectedArgs = declared + List(maskCount) { Type.INT_TYPE } + Type.getObjectType(DEFAULT_CONSTRUCTOR_MARKER)
        val synthetic = node.methods.firstOrNull {
            it.name == "<init>" && Type.getArgumentTypes(it.desc).toList() == expectedArgs
        } ?: return emptyMap()

        // Local slots: 0 is `this`; longs and doubles take two.
        val paramSlots = declared.runningFold(1) { slot, t -> slot + t.size }
        val maskSlots = (0 until maskCount).map { paramSlots.last() + it }

        val out = linkedMapOf<String, DefaultValue>()
        for ((index, slot) in paramSlots.dropLast(1).withIndex()) {
            val value = defaultFor(synthetic, maskSlots[index / 32], 1 shl (index % 32), slot, declared[index], cls.classLoader)
            if (value != null) out[names[index]!!] = value
        }
        return out
    }

    /** The constant stored into [paramSlot] when [bit] of the mask in [maskSlot] is set. */
    private fun defaultFor(
        method: MethodNode,
        maskSlot: Int,
        bit: Int,
        paramSlot: Int,
        type: Type,
        loader: ClassLoader?,
    ): DefaultValue? {
        val insns = method.instructions.toArray().filter { it.opcode >= 0 }
        for (i in 0 until insns.size - 3) {
            val load = insns[i] as? VarInsnNode ?: continue
            if (load.opcode != Opcodes.ILOAD || load.`var` != maskSlot) continue
            if (intConstant(insns[i + 1]) != bit.toLong()) continue
            if (insns[i + 2].opcode != Opcodes.IAND) continue
            val jump = insns[i + 3] as? JumpInsnNode ?: continue
            if (jump.opcode != Opcodes.IFEQ) continue

            // The default's computation runs up to the store into the parameter's slot.
            val body = insns.drop(i + 4).takeWhile { !(it is VarInsnNode && it.`var` == paramSlot && isStore(it.opcode)) }
            return constantOf(body, type, loader)
        }
        return null
    }

    private fun constantOf(body: List<AbstractInsnNode>, type: Type, loader: ClassLoader?): DefaultValue? {
        // An optional trailing `Integer.valueOf(…)` & co. boxes the constant for a nullable parameter.
        val unboxed = body.lastOrNull()
            ?.let { it as? MethodInsnNode }
            ?.takeIf { it.opcode == Opcodes.INVOKESTATIC && it.name == "valueOf" && it.owner.startsWith("java/lang/") }
            ?.let { body.dropLast(1) }
            ?: body
        val insn = unboxed.singleOrNull() ?: return null
        val boolean = type.sort == Type.BOOLEAN || type.descriptor == "Ljava/lang/Boolean;"

        if (insn is FieldInsnNode && insn.opcode == Opcodes.GETSTATIC) {
            val owner = try { Class.forName(insn.owner.replace('/', '.'), false, loader) } catch (_: Throwable) { return null }
            return if (owner.isEnum && owner.enumConstants.any { (it as Enum<*>).name == insn.name }) {
                DefaultValue.EnumValue(insn.name)
            } else null
        }
        intConstant(insn)?.let { n ->
            return if (boolean) DefaultValue.BooleanValue(n != 0L) else DefaultValue.IntegerValue(n)
        }
        if (boolean) return null
        return when (insn.opcode) {
            Opcodes.LCONST_0 -> DefaultValue.IntegerValue(0)
            Opcodes.LCONST_1 -> DefaultValue.IntegerValue(1)
            Opcodes.FCONST_0, Opcodes.DCONST_0 -> DefaultValue.NumberValue(0.0)
            Opcodes.FCONST_1, Opcodes.DCONST_1 -> DefaultValue.NumberValue(1.0)
            Opcodes.FCONST_2 -> DefaultValue.NumberValue(2.0)
            Opcodes.LDC -> when (val c = (insn as LdcInsnNode).cst) {
                is String -> DefaultValue.StringValue(c)
                is Long -> DefaultValue.IntegerValue(c)
                is Float -> DefaultValue.NumberValue(c.toString().toDouble())
                is Double -> DefaultValue.NumberValue(c)
                else -> null
            }
            else -> null
        }
    }

    /** The value pushed by an int-constant instruction, or null for any other instruction. */
    private fun intConstant(insn: AbstractInsnNode): Long? = when {
        insn is InsnNode && insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> (insn.opcode - Opcodes.ICONST_0).toLong()
        insn is IntInsnNode && (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) -> insn.operand.toLong()
        insn is LdcInsnNode && insn.cst is Int -> (insn.cst as Int).toLong()
        else -> null
    }

    private fun isStore(opcode: Int): Boolean = opcode in Opcodes.ISTORE..Opcodes.ASTORE

    private fun classNode(cls: Class<*>): ClassNode? {
        val loader = cls.classLoader ?: return null
        val bytes = loader.getResourceAsStream(cls.name.replace('.', '/') + ".class") ?: return null
        return ClassNode().also { cn -> bytes.use { ClassReader(it).accept(cn, ClassReader.SKIP_FRAMES) } }
    }
}
