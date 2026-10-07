// src/main/kotlin/community/flock/wirespec/extractor/emit/Emitter.kt
package community.flock.wirespec.extractor.emit

import arrow.core.NonEmptyList
import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.parse.ast.Annotation
import community.flock.wirespec.compiler.core.parse.ast.Channel
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint
import community.flock.wirespec.compiler.core.parse.ast.Field
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Identifier
import community.flock.wirespec.compiler.core.parse.ast.Module
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Root
import community.flock.wirespec.compiler.core.parse.ast.Rpc
import community.flock.wirespec.compiler.core.parse.ast.Type
import community.flock.wirespec.compiler.utils.Logger
import community.flock.wirespec.compiler.utils.noLogger
import community.flock.wirespec.emitters.wirespec.WirespecEmitter
import java.io.File

class Emitter {

    /**
     * Custom emitter that also backticks field names starting with `_`, and renders
     * annotations on RPCs and fields.
     *
     * The upstream [WirespecEmitter] already backticks names that are reserved
     * keywords or start with an uppercase letter, but Wirespec syntax also
     * requires underscore-leading field names to be quoted. It parses annotations
     * but does not emit them, so an RPC's `@Query` and a field's `@Default(20)`
     * would otherwise be lost.
     */
    private val emitter = object : WirespecEmitter() {
        override fun emit(identifier: Identifier): String {
            if (identifier is FieldIdentifier && identifier.value.startsWith("_")) {
                return "`${identifier.value}`"
            }
            return super.emit(identifier)
        }

        override fun emit(rpc: Rpc): String =
            rpc.annotations.joinToString("") { "${it.render()}\n" } + super.emit(rpc)

        // Same as the upstream rendering, behind the field's annotations. A string field's
        // values are always quoted, so `@Default("draft")` doesn't read as an enum entry.
        override fun Field.emit(): String {
            val quoted = reference is Reference.Primitive &&
                (reference as Reference.Primitive).type is Reference.Primitive.Type.String
            return annotations.joinToString("") { "${it.render(quoted)} " } + "${emit(identifier)}: ${reference.emit()}"
        }
    }
    private val logger: Logger = noLogger

    /**
     * Render and write all `.ws` files into [outputDir].
     *
     * - Deletes existing `*.ws` files in [outputDir] recursively.
     * - Writes one `<ControllerName>.ws` per entry of [controllerDefinitions]
     *   (each entry's `List<Definition>` may contain endpoints **and** owned types).
     * - Writes one `types.ws` containing every definition in [sharedTypes].
     * - Never touches non-`.ws` files; never writes outside [outputDir].
     */
    fun write(
        outputDir: File,
        controllerDefinitions: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): List<File> {
        outputDir.mkdirs()
        clearExistingWs(outputDir)

        val written = mutableListOf<File>()

        val (unitControllers, unitShared) = replaceEmptyTypesWithUnit(controllerDefinitions, sharedTypes)
        val (dedupedControllers, dedupedShared) = deduplicateNames(unitControllers, unitShared)

        // File names are claimed case-insensitively: on macOS/Windows `Keyword.ws` and
        // `keyword.ws` are the same file, so a second controller differing only by case
        // would silently overwrite the first. `types` is reserved for the shared file.
        val usedFileNames = mutableSetOf("types")

        dedupedControllers.forEach { (controller, defs) ->
            defs.toNonEmptyListOrNull()?.let { nel ->
                val fileName = uniqueFileName(controller, usedFileNames)
                val path = File(outputDir, "$fileName.ws")
                path.writeText(render(nel, "$fileName.ws"))
                written += path
            }
        }

        dedupedShared.toNonEmptyListOrNull()?.let { nel ->
            val path = File(outputDir, "types.ws")
            path.writeText(render(nel, "types.ws"))
            written += path
        }

        return written
    }

    /**
     * Returns [base] if no file with that name (ignoring case) has been written yet,
     * otherwise `base2`, `base3`, … The chosen name is added to [used].
     */
    private fun uniqueFileName(base: String, used: MutableSet<String>): String {
        if (used.add(base.lowercase())) return base
        var i = 2
        while (!used.add("$base$i".lowercase())) i++
        return "$base$i"
    }

    private fun render(defs: NonEmptyList<Definition>, fileName: String): String {
        val ast = Root(
            modules = NonEmptyList(
                head = Module(fileUri = FileUri(fileName), statements = defs),
                tail = emptyList(),
            )
        )
        return emitter.emit(ast, logger).head.result
    }

    /** `@Name`, `@Name(value)`, or `@Name(key: value, …)` — the syntax Wirespec parses. */
    private fun Annotation.render(quoted: Boolean = false): String {
        if (parameters.isEmpty()) return "@$name"
        val args = parameters.joinToString(", ") { p ->
            (if (p.name == "default") "" else "${p.name}: ") + p.value.render(quoted)
        }
        return "@$name($args)"
    }

    private fun Annotation.Value.render(quoted: Boolean): String = when (this) {
        // Numbers, booleans, and identifiers (enum entries) stay bare unless [quoted]; anything else is quoted.
        is Annotation.Value.Single -> if (!quoted && BARE_VALUE.matches(value)) value else "\"$value\""
        is Annotation.Value.Array -> value.joinToString(", ", "[", "]") { it.render(quoted) }
        is Annotation.Value.Dict -> value.joinToString(", ", "{", "}") { "${it.name}: ${it.value.render(quoted)}" }
    }

    private fun clearExistingWs(dir: File) {
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "ws" }
            .forEach { it.delete() }
    }

    /**
     * Wirespec forbids empty types (`type Foo {}`), so drop every object type
     * that has no fields and rewrite every reference to it into `Unit` — the
     * canonical "no content" reference. This covers all reference sites
     * (channel payloads, endpoint request/response bodies, path params, query
     * and header fields, RPC arguments and results, and fields of other types),
     * which also guarantees no dangling reference is left pointing at a
     * definition we removed.
     *
     * Runs before [deduplicateNames] so the freed type names are reflected in
     * the collision counts.
     */
    private fun replaceEmptyTypesWithUnit(
        controllerDefinitions: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): Pair<Map<String, List<Definition>>, List<Definition>> {
        val emptyTypeNames = (controllerDefinitions.values.flatten() + sharedTypes)
            .filterIsInstance<Type>()
            .filter { it.shape.value.isEmpty() && it.extends.isEmpty() }
            .mapTo(mutableSetOf()) { it.identifier.value }

        if (emptyTypeNames.isEmpty()) return controllerDefinitions to sharedTypes

        fun rewriteRef(ref: Reference): Reference = when (ref) {
            is Reference.Custom   -> if (ref.value in emptyTypeNames) Reference.Unit(ref.isNullable) else ref
            is Reference.Iterable -> ref.copy(reference = rewriteRef(ref.reference))
            is Reference.Dict     -> ref.copy(reference = rewriteRef(ref.reference))
            else                  -> ref
        }

        fun rewriteContent(content: Endpoint.Content?): Endpoint.Content? =
            content?.copy(reference = rewriteRef(content.reference))

        fun rewrite(def: Definition): Definition = when (def) {
            is Endpoint -> def.copy(
                path = def.path.map { seg ->
                    if (seg is Endpoint.Segment.Param) seg.copy(reference = rewriteRef(seg.reference)) else seg
                },
                queries = def.queries.map { it.copy(reference = rewriteRef(it.reference)) },
                headers = def.headers.map { it.copy(reference = rewriteRef(it.reference)) },
                requests = def.requests.map { it.copy(content = rewriteContent(it.content)) },
                responses = def.responses.map { it.copy(content = rewriteContent(it.content)) },
            )
            is Channel -> def.copy(reference = rewriteRef(def.reference))
            is Rpc -> def.copy(
                shape = def.shape.copy(
                    value = def.shape.value.map { it.copy(reference = rewriteRef(it.reference)) }
                ),
                result = rewriteRef(def.result),
                error = def.error?.let(::rewriteRef),
            )
            is Type -> def.copy(
                shape = def.shape.copy(
                    value = def.shape.value.map { it.copy(reference = rewriteRef(it.reference)) }
                )
            )
            else -> def
        }

        fun rewriteDefs(defs: List<Definition>): List<Definition> = defs
            .filterNot { it is Type && it.identifier.value in emptyTypeNames }
            .map(::rewrite)

        val newControllers = controllerDefinitions.mapValues { (_, defs) -> rewriteDefs(defs) }
        val newShared = rewriteDefs(sharedTypes)
        return newControllers to newShared
    }

    /**
     * Ensure every definition is uniquely named across *all* emitted files, not
     * just within one. A name that appears exactly once anywhere stays as-is; a
     * name that appears two or more times (endpoint↔endpoint across files,
     * endpoint↔channel, rpc↔endpoint, endpoint↔type, …) gets a numeric suffix
     * on *every* occurrence — `Foo1`, `Foo2`, `Foo3` — so no "winner" silently
     * keeps the bare name.
     *
     * Types are never renamed: they're referenced by name from endpoints,
     * channels, and other types (across files via `types.ws`), and renaming
     * them would break those references. Every type name is reserved up front;
     * when a type and an endpoint/channel share a name, only the
     * endpoint/channel receives a suffix.
     *
     * Renaming endpoints/channels/RPCs is safe globally because nothing references
     * them — they are leaves in the reference graph.
     */
    private fun deduplicateNames(
        controllerDefinitions: Map<String, List<Definition>>,
        sharedTypes: List<Definition>,
    ): Pair<Map<String, List<Definition>>, List<Definition>> {
        val allDefs = controllerDefinitions.values.flatten() + sharedTypes
        // Collisions are detected case-insensitively: `Foo` and `foo` share the
        // one emitted namespace and must not both keep their bare name, so they
        // are counted and reserved by their lowercased form.
        val nameCounts = allDefs.groupingBy { it.identifier.value.lowercase() }.eachCount()
        // Reserve every type name first so a suffixed endpoint never collides
        // with a type called `Foo1` either.
        val used = allDefs
            .filterNot { it.isOperation() }
            .mapTo(mutableSetOf()) { it.identifier.value.lowercase() }

        fun rename(def: Definition): Definition {
            val name = def.identifier.value
            return when {
                !def.isOperation() -> def
                nameCounts.getValue(name.lowercase()) == 1 -> {
                    used.add(name.lowercase())
                    def
                }
                else -> {
                    var i = 1
                    while (!used.add("$name$i".lowercase())) i++
                    val newName = "$name$i"
                    when (def) {
                        is Endpoint -> def.copy(identifier = DefinitionIdentifier(newName))
                        is Channel  -> def.copy(identifier = DefinitionIdentifier(newName))
                        is Rpc      -> def.copy(identifier = DefinitionIdentifier(newName))
                        else        -> def
                    }
                }
            }
        }

        // Assign suffixes in a deterministic order — controllers by name, then
        // shared types — so emitted names don't shuffle when the classpath scan
        // order changes between builds.
        val dedupedControllers = linkedMapOf<String, List<Definition>>()
        controllerDefinitions.keys.sorted().forEach { controller ->
            dedupedControllers[controller] = controllerDefinitions.getValue(controller).map(::rename)
        }
        val dedupedShared = sharedTypes.map(::rename)
        return dedupedControllers to dedupedShared
    }

    /** Endpoints, channels, and RPCs: the renameable leaves of the reference graph. */
    private fun Definition.isOperation(): Boolean = this is Endpoint || this is Channel || this is Rpc

    private companion object {
        val BARE_VALUE = Regex("true|false|[0-9]+(\\.[0-9]+)?|[A-Za-z_][A-Za-z0-9_]*")
    }
}
