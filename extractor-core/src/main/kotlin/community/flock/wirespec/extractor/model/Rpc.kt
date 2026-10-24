package community.flock.wirespec.extractor.model

/**
 * Internal domain model for a Wirespec RPC function — a GraphQL query, mutation, or
 * subscription. Parallel to [Endpoint] and [Channel].
 *
 * @property ownerSimpleName Simple name of the class that owns this RPC — drives
 *   the per-class .ws file grouping used by the emitter.
 * @property field The GraphQL field name of the operation on its root type.
 * @property kind Which GraphQL root type the operation belongs to.
 * @property rootTypeName The GraphQL object that owns the field. Defaults to
 *   the standard root for [kind], but may be a nested operation namespace such
 *   as `NotificationsQuery`.
 * @property arguments The operation's arguments, in declaration order.
 * @property result The operation's result type; `null` when the handler returns nothing.
 * @property name PascalCase identifier used as the Wirespec definition name — the field
 *   name until a clash with another definition prefixes it with the root type.
 */
data class Rpc(
    val ownerSimpleName: String,
    val field: String,
    val kind: Kind,
    val arguments: List<WireType.Field>,
    val result: WireType?,
    val rootTypeName: String = kind.typeName,
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
