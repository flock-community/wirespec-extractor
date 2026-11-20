package community.flock.wirespec.extractor.model

/**
 * Internal domain model for a Wirespec RPC function — a GraphQL query, mutation, or
 * subscription, or a field with arguments of a type that holds data. Parallel to
 * [Endpoint] and [Channel].
 *
 * @property ownerSimpleName Simple name of the class that owns this RPC — drives
 *   the per-class .ws file grouping used by the emitter.
 * @property field The GraphQL field name of the RPC on its parent type.
 * @property kind Which GraphQL root type the operation belongs to. A field of a type
 *   that holds data is a [Kind.QUERY], as Wirespec's GraphQL converter writes it.
 * @property arguments The field's arguments, in declaration order.
 * @property result The field's result type; `null` when the handler returns nothing.
 * @property parentTypeName The GraphQL object that owns the field. Defaults to the
 *   standard root for [kind], but may be an operation namespace nested in it, such as
 *   `NotificationsQuery`, or, when [ofDataType], a type such as `Book`.
 * @property ofDataType The RPC is a field with arguments of a type that holds data
 *   (`Book.reviews`): a function of that type, not an operation of its own.
 * @property name PascalCase identifier used as the Wirespec definition name — the field
 *   name until a clash with another definition prefixes it with the parent type.
 */
data class Rpc(
    val ownerSimpleName: String,
    val field: String,
    val kind: Kind,
    val arguments: List<WireType.Field>,
    val result: WireType?,
    val parentTypeName: String = kind.typeName,
    val ofDataType: Boolean = false,
    val name: String = field.trimStart('_').replaceFirstChar(Char::uppercase),
) {
    /** The GraphQL operation type, named after its root type in the schema. */
    enum class Kind(val typeName: String) {
        QUERY("Query"),
        MUTATION("Mutation"),
        SUBSCRIPTION("Subscription"),
        ;

        companion object {
            fun of(typeName: String): Kind? = entries.firstOrNull { it.typeName == typeName }
        }
    }
}
