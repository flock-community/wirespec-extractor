package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.GraphQlField
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType

/**
 * Which object types are GraphQL input objects, decided the way Wirespec's GraphQL
 * converter reads them back (Wirespec 0.22): GraphQL only takes input objects as
 * arguments, so a type an argument reaches — directly or through its fields — is an
 * input. When such a type is also returned, the converter writes it twice, as an object
 * type and as an input object named `...Input`.
 *
 * @property inputOnly Types that are only ever taken: GraphQL `input`s, marked `@GraphQLInput`.
 * @property inputNames The GraphQL input name of every type an argument reaches; `...Input`
 *   for a type that is also returned.
 */
internal data class GraphQlInputs(
    val inputOnly: Set<String>,
    val inputNames: Map<String, String>,
) {
    companion object {
        val NONE = GraphQlInputs(emptySet(), emptyMap())

        /**
         * @param objects The fields' types of every extracted object type, by type name.
         * @param rpcs The root operations; their arguments are inputs, their results outputs.
         * @param fields The non-root field resolvers; likewise.
         */
        fun of(objects: Map<String, List<WireType>>, rpcs: List<Rpc>, fields: List<GraphQlField>): GraphQlInputs {
            fun reach(roots: List<WireType>): Set<String> {
                val reached = linkedSetOf<String>()
                val frontier = ArrayDeque(roots.flatMap(::refNames))
                while (frontier.isNotEmpty()) {
                    val name = frontier.removeFirst()
                    val fieldTypes = objects[name] ?: continue
                    if (reached.add(name)) fieldTypes.forEach { frontier += refNames(it) }
                }
                return reached
            }

            val inputs = reach((rpcs.flatMap { it.arguments } + fields.flatMap { it.arguments }).map { it.type })
            if (inputs.isEmpty()) return NONE
            // Returned, or a field of a type that isn't itself an input.
            val outputs = reach(
                rpcs.mapNotNull { it.result } +
                    fields.mapNotNull { it.result } +
                    objects.filterKeys { it !in inputs }.values.flatten(),
            )
            return GraphQlInputs(
                inputOnly = inputs - outputs,
                inputNames = inputs.associateWith { if (it in outputs) "${it}Input" else it },
            )
        }

        private fun refNames(type: WireType): List<String> = when (type) {
            is WireType.Ref    -> listOf(type.name)
            is WireType.ListOf -> refNames(type.element)
            is WireType.MapOf  -> refNames(type.value)
            else               -> emptyList()
        }
    }
}
