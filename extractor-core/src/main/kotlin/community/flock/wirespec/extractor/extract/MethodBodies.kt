package community.flock.wirespec.extractor.extract

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Method

/**
 * Loads the compiled body of a handler method, for the scanners that read what a handler
 * does rather than what its signature declares.
 */
internal object MethodBodies {

    /** Suffix of the synthetic static method holding an `open` suspend function's body. */
    private const val SUSPEND_IMPL_SUFFIX = "\$suspendImpl"

    /**
     * The code of a method.
     *
     * @property owner internal name of the class declaring [node].
     * @property node  the method holding the code: the method itself, or the `$suspendImpl`
     *                 it forwards to (see [suspendImplOf]).
     */
    class Body(val owner: String, val node: MethodNode)

    /** The body of [method], read from its declaring class's bytecode; null when unavailable. */
    fun of(method: Method): Body? {
        val loader = method.declaringClass.classLoader ?: return null
        val internalName = Type.getInternalName(method.declaringClass)
        val bytes = loader.getResourceAsStream("$internalName.class") ?: return null
        val cn = ClassNode()
        bytes.use { ClassReader(it).accept(cn, ClassReader.SKIP_FRAMES) }
        val desc = Type.getMethodDescriptor(method)
        val node = cn.methods.firstOrNull { it.name == method.name && it.desc == desc }
            ?: cn.methods.firstOrNull { it.name == method.name }
            ?: return null
        return Body(internalName, suspendImplOf(cn, node, internalName) ?: node)
    }

    /**
     * An `open` suspend function does not hold its own body: Kotlin moves the
     * state machine into a synthetic static `<name>$suspendImpl` and leaves the
     * open method as a bridge that just forwards to it. The calls a scanner looks
     * for therefore live in `$suspendImpl`, not in the method matching the
     * signature — so scanning the bridge finds nothing.
     *
     * Every Spring handler is affected once the `all-open` compiler plugin is on
     * (`kotlin-spring`, i.e. effectively every Spring Boot Kotlin project), which
     * opens `@RestController` classes and their members; Quarkus Kotlin projects
     * open `@Path` classes the same way.
     *
     * Returns the `$suspendImpl` node when [node] is such a bridge, else null.
     */
    private fun suspendImplOf(cn: ClassNode, node: MethodNode, owner: String): MethodNode? {
        val target = node.name + SUSPEND_IMPL_SUFFIX
        val forwards = node.instructions.toArray().any {
            it is MethodInsnNode && it.opcode == Opcodes.INVOKESTATIC && it.owner == owner && it.name == target
        }
        return if (forwards) cn.methods.firstOrNull { it.name == target } else null
    }
}
