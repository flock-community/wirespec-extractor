package community.flock.wirespec.extractor.model

/**
 * A field without arguments resolved by Spring for GraphQL through `@SchemaMapping` on a
 * type that holds data. A field with arguments is a function, and so an [Rpc].
 */
data class GraphQlField(
    val parentTypeName: String,
    val field: String,
    val result: WireType?,
)

/**
 * A field of [parentTypeName] that only leads to the operation namespace [typeName],
 * `Query.library: LibraryQuery!`. It is no RPC: the header of every file with an RPC of
 * the namespace names it instead, as Wirespec's GraphQL converter reads it back:
 * `@file:GraphQLQuery("Query", path: { library: "LibraryQuery!" })`.
 *
 * @property kind The operation type of the namespace, which the header annotation names.
 */
data class GraphQlNesting(
    val kind: Rpc.Kind,
    val parentTypeName: String,
    val field: String,
    val typeName: String,
    val nullable: Boolean,
)
