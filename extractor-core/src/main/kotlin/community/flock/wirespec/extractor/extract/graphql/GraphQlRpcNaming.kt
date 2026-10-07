package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.Rpc

/**
 * Names RPCs the way Wirespec's GraphQL converter does (wirespec PR #710), so the extracted
 * spec reads back as the same schema: an RPC is named after its field (`addTodo` becomes
 * `AddTodo`) and only takes its root type as prefix when that name is not a usable type name
 * or is already taken — by a type (`type Book` for a field `book` gives `QueryBook`) or by an
 * earlier RPC. A name still taken after that gets a numeric suffix.
 */
internal object GraphQlRpcNaming {

    private val TYPE_NAME = Regex("[A-Z][a-zA-Z0-9_]*")

    private val RESERVED = setOf(
        "Any", "Boolean", "Bytes", "Integer", "Integer32", "Number", "Number32", "String", "Unit",
        "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE",
    )

    /** [rpcs], in order, each named so it clashes with neither [typeNames] nor an earlier RPC. */
    fun name(rpcs: List<Rpc>, typeNames: Set<String>): List<Rpc> {
        val taken = typeNames.toMutableSet()
        return rpcs.map { rpc ->
            val candidate = rpc.field.trimStart('_').replaceFirstChar(Char::uppercase)
            val base = if (TYPE_NAME.matches(candidate) && candidate !in RESERVED && candidate !in taken) {
                candidate
            } else {
                rpc.kind.typeName + candidate
            }
            val name = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base$it" })
                .first { it !in taken }
            taken += name
            rpc.copy(name = name)
        }
    }
}
