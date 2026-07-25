// src/main/kotlin/community/flock/wirespec/extractor/extract/ResponseEntityStatusScanner.kt
package community.flock.wirespec.extractor.extract

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.springframework.http.HttpStatus
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.lang.reflect.Method

/**
 * Recovers the HTTP status a Spring handler sets *inside its body* when it
 * returns a `ResponseEntity` built with an explicit status — e.g.
 * `ResponseEntity.status(HttpStatus.CREATED).body(x)`, `ResponseEntity.created(uri)`,
 * `ResponseEntity.status(201)`, or `new ResponseEntity<>(x, HttpStatus.CREATED)`.
 *
 * The rest of the extractor works purely from a method's signature and
 * annotations, which cannot see a status chosen programmatically in the body.
 * This walks the compiled method with ASM to fill that gap. It is deliberately
 * conservative: it only reports a status when the body builds a *single*
 * successful (2xx) status, and returns null otherwise so the caller falls back
 * to the signature-derived default (200/204) or an explicit `@ResponseStatus` /
 * `@ApiResponse`.
 *
 * Kotlin `suspend` handlers compile their body into the same method (as a state
 * machine), so the `ResponseEntity` calls are visible here just as for a plain
 * method.
 */
object ResponseEntityStatusScanner {

    private const val RESPONSE_ENTITY = "org/springframework/http/ResponseEntity"
    private const val HTTP_STATUS = "org/springframework/http/HttpStatus"

    /**
     * `ResponseEntity` static factory methods whose name implies a fixed status.
     * (`ok`/`created`/… map to their canonical codes; `status(...)` is handled
     * separately because it carries the code as an argument.)
     */
    private val FACTORY_STATUS = mapOf(
        "ok" to 200,
        "created" to 201,
        "accepted" to 202,
        "noContent" to 204,
        "badRequest" to 400,
        "notFound" to 404,
        "unprocessableEntity" to 422,
        "internalServerError" to 500,
    )

    /** The status set on the ResponseEntity built by [method], or null when absent/ambiguous. */
    fun scan(method: Method): Int? {
        val node = methodNode(method) ?: return null

        val found = LinkedHashSet<Int>()
        // The status-carrying value most recently pushed onto the stack. Reset
        // after every method call so a value is only attributed to the call that
        // immediately consumes it.
        var pendingStatusName: String? = null
        var pendingIntConst: Int? = null

        var insn = node.instructions.first
        while (insn != null) {
            when (insn) {
                is FieldInsnNode ->
                    if (insn.opcode == Opcodes.GETSTATIC && insn.owner == HTTP_STATUS) {
                        pendingStatusName = insn.name
                    }

                is IntInsnNode -> pendingIntConst = insn.operand // BIPUSH / SIPUSH
                is LdcInsnNode -> (insn.cst as? Int)?.let { pendingIntConst = it }

                is MethodInsnNode -> {
                    if (insn.owner == RESPONSE_ENTITY) {
                        when {
                            insn.name in FACTORY_STATUS -> found += FACTORY_STATUS.getValue(insn.name)
                            // status(HttpStatusCode) / status(int) / new ResponseEntity(body, HttpStatus)
                            insn.name == "status" || insn.name == "<init>" ->
                                (statusFromName(pendingStatusName) ?: pendingIntConst)?.let { found += it }
                        }
                    }
                    pendingStatusName = null
                    pendingIntConst = null
                }
            }
            insn = insn.next
        }

        // Only a single, unambiguous success status is trustworthy as the
        // endpoint's natural status; error branches and conflicting successes
        // are left to explicit annotations.
        return found.filter { it in 200..299 }.singleOrNull()
    }

    private fun statusFromName(name: String?): Int? =
        name?.let { runCatching { HttpStatus.valueOf(it).value() }.getOrNull() }

    private fun methodNode(method: Method): MethodNode? {
        val loader = method.declaringClass.classLoader ?: return null
        val internalName = Type.getInternalName(method.declaringClass)
        val bytes = loader.getResourceAsStream("$internalName.class") ?: return null
        val cn = ClassNode()
        bytes.use { ClassReader(it).accept(cn, ClassReader.SKIP_FRAMES) }
        val desc = Type.getMethodDescriptor(method)
        return cn.methods.firstOrNull { it.name == method.name && it.desc == desc }
            ?: cn.methods.firstOrNull { it.name == method.name }
    }
}
