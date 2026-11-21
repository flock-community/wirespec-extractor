package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.GraphQlNesting
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType

/**
 * Nests operation namespaces the way Wirespec's GraphQL converter does (Wirespec 0.23.10).
 *
 * A schema can group operations in a type that a field of a root type returns,
 * `Query.library: LibraryQuery!`, whose fields `@SchemaMapping(typeName = "LibraryQuery")`
 * resolves. The field that leads there only opens the namespace, so it is no rpc: the
 * header of every file with an rpc of the namespace names it, and the namespace needs no
 * definition of its own.
 *
 * A field opens a namespace when it takes no arguments and returns nothing or an object
 * without fields. It opens the namespace its result is named after, or else the
 * `<Field><Root>` one: `library` on `Query` opens `LibraryQuery`. A namespace can nest
 * another: a field of `LibraryQuery` can open `ArchiveQuery`.
 *
 * @property operations The rpcs, without the fields that open a namespace.
 */
internal class GraphQlNamespaces private constructor(
    val operations: List<Rpc>,
    private val nestings: Map<String, GraphQlNesting>,
) {

    /**
     * The fields that lead from a root type to the namespaces [rpcs] are fields of, each
     * once and every one after the field that leads to its parent type: what the header of
     * the file [rpcs] are written in names.
     */
    fun path(rpcs: List<Rpc>): List<GraphQlNesting> = rpcs
        .flatMap { rpc ->
            generateSequence(nestings[rpc.parentTypeName]) { nestings[it.parentTypeName] }
                .take(nestings.size)
                .toList()
                .asReversed()
        }
        .distinct()

    companion object {

        /** @param emptyTypes The object types without fields, looked up only when a field could open a namespace. */
        fun of(rpcs: List<Rpc>, emptyTypes: () -> Set<String>): GraphQlNamespaces {
            val namespaces = rpcs
                .filterNot { it.ofDataType || it.parentTypeName == it.kind.typeName }
                .groupBy({ it.kind }, { it.parentTypeName })
                .mapValues { (_, names) -> names.toSet() }
            val empty by lazy(emptyTypes)

            fun Rpc.opens(): String? {
                if (ofDataType || arguments.isNotEmpty()) return null
                val declared = namespaces[kind].orEmpty()
                val named = (result as? WireType.Ref)?.name
                return when {
                    result != null && named !in empty -> null
                    named in declared -> named
                    else -> (name + kind.typeName).takeIf { it in declared }
                }
            }

            val openers = rpcs.mapNotNull { rpc -> rpc.opens()?.let { rpc to it } }
            return GraphQlNamespaces(
                operations = rpcs - openers.map { it.first }.toSet(),
                nestings = openers
                    .map { (rpc, namespace) -> GraphQlNesting(rpc.kind, rpc.parentTypeName, rpc.field, namespace, rpc.result?.nullable ?: false) }
                    .distinctBy { it.typeName }
                    .associateBy { it.typeName },
            )
        }
    }
}
