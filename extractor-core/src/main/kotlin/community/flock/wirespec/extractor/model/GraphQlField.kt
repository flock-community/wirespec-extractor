package community.flock.wirespec.extractor.model

/**
 * A field resolved by Spring for GraphQL through `@SchemaMapping` on a non-root type.
 */
data class GraphQlField(
    val parentTypeName: String,
    val field: String,
    val arguments: List<WireType.Field>,
    val result: WireType?,
)
