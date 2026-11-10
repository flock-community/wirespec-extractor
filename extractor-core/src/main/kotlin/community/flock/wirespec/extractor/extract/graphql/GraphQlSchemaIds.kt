package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.model.GraphQlField
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType
import io.github.classgraph.ClassGraph

/**
 * Puts GraphQL's built-in `ID` scalar back where the schema declares it.
 *
 * On the JVM an `ID` is a plain `String` (or `Int` / `Long`); Netflix DGS codegen and
 * hand-written Spring for GraphQL types alike carry nothing that tells it apart from a
 * `String`. A client that declares such an argument as `String!` is rejected by the server
 * (`Variable '$id' of type 'String!' used in position expecting type 'ID!'`), so the
 * GraphQL schema files on the classpath are read, and every argument, result, and object or
 * input field the schema types as `ID` is retyped to Wirespec's `@GraphQLBuiltIn type ID =
 * String` — the shape Wirespec's GraphQL converter reads an `ID` into and writes back out.
 *
 * Fields are matched by GraphQL type and field name: root operations by their root type
 * (`Query`, a namespace such as `LibraryQuery`, …), schema-mapped fields by their parent
 * type, and object types by the extracted type's name — which is the GraphQL type's name
 * for types generated from the schema. Only a scalar the JVM type agrees with (a string or
 * integer, through the same list nesting) is retyped; anything else is left as extracted.
 */
internal class GraphQlSchemaIds private constructor(
    private val fields: Map<String, Map<String, GraphQlSdl.TypeRef>>,
    private val arguments: Map<String, Map<String, Map<String, GraphQlSdl.TypeRef>>>,
    private val claimIdName: () -> String,
) {

    private val idName by lazy(claimIdName)
    private var used = false

    /** The `ID` scalar's definition, once anything has been retyped to it. */
    val idDefinition: WireType.Refined?
        get() = if (used) WireType.Refined(idName, WireType.Primitive(WireType.Primitive.Kind.STRING), builtIn = true) else null

    fun rpc(rpc: Rpc): Rpc = rpc.copy(
        arguments = arguments(rpc.rootTypeName, rpc.field, rpc.arguments),
        result = rpc.result?.let { retype(it, fields[rpc.rootTypeName]?.get(rpc.field)) },
    )

    fun field(field: GraphQlField): GraphQlField = field.copy(
        arguments = arguments(field.parentTypeName, field.field, field.arguments),
        result = field.result?.let { retype(it, fields[field.parentTypeName]?.get(field.field)) },
    )

    fun definition(definition: WireType): WireType {
        if (definition !is WireType.Object) return definition
        val schemaFields = fields[definition.name] ?: return definition
        return definition.copy(fields = definition.fields.map { it.copy(type = retype(it.type, schemaFields[it.name])) })
    }

    private fun arguments(typeName: String, field: String, extracted: List<WireType.Field>): List<WireType.Field> {
        val schemaArguments = arguments[typeName]?.get(field) ?: return extracted
        return extracted.map { it.copy(type = retype(it.type, schemaArguments[it.name])) }
    }

    /** [type] with every position [schema] types as `ID` retyped, nullability kept as extracted. */
    private fun retype(type: WireType, schema: GraphQlSdl.TypeRef?): WireType = when (schema) {
        null -> type
        is GraphQlSdl.TypeRef.NonNull -> retype(type, schema.type)
        is GraphQlSdl.TypeRef.ListOf ->
            if (type is WireType.ListOf) type.copy(element = retype(type.element, schema.type)) else type
        is GraphQlSdl.TypeRef.Named ->
            if (schema.name == ID && type is WireType.Primitive && type.kind in ID_KINDS) {
                used = true
                WireType.Ref(idName, type.nullable)
            } else type
    }

    companion object {
        /** GraphQL's built-in `ID` scalar. */
        const val ID = "ID"

        /** JVM representations Spring for GraphQL and DGS coerce an `ID` from. */
        private val ID_KINDS = setOf(
            WireType.Primitive.Kind.STRING,
            WireType.Primitive.Kind.INTEGER_32,
            WireType.Primitive.Kind.INTEGER_64,
        )

        /**
         * Where the schema is loaded from: Spring for GraphQL's default
         * `classpath:graphql/` and DGS's `classpath*:schema/`, which is also where DGS
         * codegen reads it from (`src/main/resources/schema`).
         */
        private val LOCATIONS = arrayOf("graphql", "schema")
        private val EXTENSIONS = listOf("graphqls", "graphql", "gqls")

        /** Retypes nothing: there is nothing GraphQL to retype. */
        val NONE: GraphQlSchemaIds get() = of(emptyList()) { ID }

        /**
         * Reads every GraphQL schema file under [LOCATIONS] on [classLoader] — the project's
         * own resources and those of its dependencies, as the server itself would.
         *
         * @param claimIdName Claims the Wirespec name of the `ID` definition; called only once
         *   something is retyped to it.
         */
        fun load(
            classLoader: ClassLoader,
            claimIdName: () -> String,
            onInfo: (String) -> Unit = {},
            onWarn: (String) -> Unit = {},
        ): GraphQlSchemaIds {
            val schemas = ClassGraph()
                .overrideClassLoaders(classLoader)
                .ignoreParentClassLoaders()
                .acceptPaths(*LOCATIONS)
                .scan()
                .use { result ->
                    EXTENSIONS
                        .flatMap { result.getResourcesWithExtension(it) }
                        .distinctBy { it.uri }
                        .sortedBy { it.path }
                        .mapNotNull { resource ->
                            try {
                                GraphQlSdl.parse(resource.contentAsString)
                            } catch (t: GraphQlSdl.SyntaxException) {
                                onWarn("graphql: skipping schema ${resource.path}: ${t.message}")
                                null
                            }
                        }
                }
            if (schemas.isNotEmpty()) onInfo("Read ${schemas.size} GraphQL schema file(s)")
            return of(schemas.flatten(), claimIdName)
        }

        /** The lookup over parsed schema [definitions]; type extensions merge into their type. */
        fun of(definitions: List<GraphQlSdl.TypeDefinition>, claimIdName: () -> String): GraphQlSchemaIds {
            val fields = mutableMapOf<String, MutableMap<String, GraphQlSdl.TypeRef>>()
            val arguments = mutableMapOf<String, MutableMap<String, Map<String, GraphQlSdl.TypeRef>>>()
            for (definition in definitions) {
                for (f in definition.fields) {
                    fields.getOrPut(definition.name, ::mutableMapOf)[f.name] = f.type
                    arguments.getOrPut(definition.name, ::mutableMapOf)[f.name] = f.arguments
                }
            }
            return GraphQlSchemaIds(fields, arguments, claimIdName)
        }
    }
}
