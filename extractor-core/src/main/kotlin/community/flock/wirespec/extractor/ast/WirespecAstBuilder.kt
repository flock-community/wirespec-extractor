// src/main/kotlin/community/flock/wirespec/extractor/ast/WirespecAstBuilder.kt
package community.flock.wirespec.extractor.ast

import community.flock.wirespec.compiler.core.parse.ast.Annotation
import community.flock.wirespec.compiler.core.parse.ast.Channel as WsChannel
import community.flock.wirespec.compiler.core.parse.ast.Comment
import community.flock.wirespec.compiler.core.parse.ast.Definition
import community.flock.wirespec.compiler.core.parse.ast.DefinitionIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Endpoint as WsEndpoint
import community.flock.wirespec.compiler.core.parse.ast.Enum as WsEnum
import community.flock.wirespec.compiler.core.parse.ast.Field as WsField
import community.flock.wirespec.compiler.core.parse.ast.FieldIdentifier
import community.flock.wirespec.compiler.core.parse.ast.Reference
import community.flock.wirespec.compiler.core.parse.ast.Refined as WsRefined
import community.flock.wirespec.compiler.core.parse.ast.Rpc as WsRpc
import community.flock.wirespec.compiler.core.parse.ast.Shape
import community.flock.wirespec.compiler.core.parse.ast.Type as WsType
import community.flock.wirespec.extractor.model.Channel
import community.flock.wirespec.extractor.model.Endpoint
import community.flock.wirespec.extractor.model.Endpoint.HttpMethod
import community.flock.wirespec.extractor.model.Endpoint.PathSegment
import community.flock.wirespec.extractor.model.GraphQlField
import community.flock.wirespec.extractor.model.GraphQlNesting
import community.flock.wirespec.extractor.model.Param
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType

class WirespecAstBuilder {

    fun toEndpoint(ep: Endpoint): WsEndpoint = WsEndpoint(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(ep.name),
        method = ep.method.toWs(),
        path = ep.pathSegments.map { it.toWs() },
        queries = Shape(ep.queryParams.map { it.toField() }),
        headers = Shape(ep.headerParams.map { it.toField() }),
        requests = listOf(
            WsEndpoint.Request(
                content = ep.requestBody?.let { WsEndpoint.Content("application/json", toReference(it)) }
            )
        ),
        responses = ep.responses.map { r ->
            WsEndpoint.Response(
                status = r.statusCode.toString(),
                headers = Shape(emptyList()),
                content = r.body?.let { WsEndpoint.Content("application/json", toReference(it)) },
                annotations = emptyList(),
            )
        },
    )

    fun toChannel(c: Channel): WsChannel = WsChannel(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(c.name),
        reference = toReference(c.payload),
    )

    /**
     * An RPC, annotated the way Wirespec's GraphQL converter (Wirespec 0.23.10) does: with its
     * operation type — `@GraphQLQuery`, `@GraphQLMutation`, or `@GraphQLSubscription` — naming
     * the type it is a field of when that is not the root type (`@GraphQLQuery("LibraryQuery")`,
     * `@GraphQLQuery("Book")`), and with `@GraphQLName` always, so the original field name is
     * never lost.
     */
    fun toRpc(r: Rpc): WsRpc = WsRpc(
        comment = null,
        annotations = listOf(
            Annotation(
                r.kind.annotationName,
                listOfNotNull(
                    Annotation.Parameter("default", Annotation.Value.Single(r.parentTypeName))
                        .takeIf { r.parentTypeName != r.kind.typeName },
                ),
            ),
            Annotation("GraphQLName", listOf(Annotation.Parameter("default", Annotation.Value.Single(r.field)))),
        ),
        identifier = DefinitionIdentifier(r.name),
        shape = Shape(r.arguments.map { it.toField() }),
        result = r.result?.let(::toReference) ?: Reference.Unit(false),
        error = null,
    )

    /**
     * A GraphQL object type holding the fields without arguments that `@SchemaMapping`s
     * resolve on it. Its fields with arguments are RPCs that name it, so it may be empty.
     */
    fun toGraphQlType(name: String, fields: List<GraphQlField>): WsType = WsType(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(name),
        shape = Shape(
            fields.map { field ->
                WsField(
                    annotations = emptyList(),
                    identifier = FieldIdentifier(field.field),
                    reference = field.result?.let(::toReference) ?: Reference.Unit(false),
                )
            },
        ),
        extends = emptyList(),
    )

    /**
     * The `@file:` annotations that nest operation namespaces, the way Wirespec's GraphQL
     * converter (Wirespec 0.23.10) writes them: one for every type with fields that lead to
     * a namespace, naming the type and those fields, each with the GraphQL type it returns.
     *
     * ```wirespec
     * @file:GraphQLQuery("Query", path: { library: "LibraryQuery!" })
     * ```
     */
    fun toFileAnnotations(path: List<GraphQlNesting>): List<Annotation> = path
        .groupBy { it.kind to it.parentTypeName }
        .map { (parent, fields) ->
            val (kind, parentTypeName) = parent
            Annotation(
                kind.annotationName,
                listOf(
                    Annotation.Parameter("default", Annotation.Value.Single(parentTypeName)),
                    Annotation.Parameter(
                        "path",
                        Annotation.Value.Dict(fields.map { Annotation.Parameter(it.field, Annotation.Value.Single(it.graphQlType())) }),
                    ),
                ),
            )
        }

    /** The GraphQL type a field of a path returns: the namespace object itself, never a list. */
    private fun GraphQlNesting.graphQlType(): String = if (nullable) typeName else "$typeName!"

    fun toDefinition(wt: WireType): Definition = when (wt) {
        is WireType.Object -> WsType(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            shape = Shape(wt.fields.map { it.toField() }),
            extends = emptyList(),
        )
        is WireType.EnumDef -> WsEnum(
            comment = wt.description?.let { Comment(it) },
            annotations = emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            entries = wt.values.toSet(),
        )
        is WireType.Refined -> WsRefined(
            comment = null,
            annotations = if (wt.builtIn) listOf(Annotation("GraphQLBuiltIn", emptyList())) else emptyList(),
            identifier = DefinitionIdentifier(wt.name),
            reference = buildRefinedReference(wt),
        )
        else -> throw IllegalArgumentException("Not a top-level definition: $wt")
    }

    fun toReference(wt: WireType): Reference = when (wt) {
        is WireType.Any       -> Reference.Any(wt.nullable)
        is WireType.Primitive -> primitiveRef(wt)
        is WireType.Ref       -> Reference.Custom(wt.name, wt.nullable)
        is WireType.ListOf    -> Reference.Iterable(toReference(wt.element), wt.nullable)
        is WireType.MapOf     -> Reference.Dict(toReference(wt.value), wt.nullable)
        is WireType.Object    -> Reference.Custom(wt.name, wt.nullable)
        is WireType.EnumDef   -> Reference.Custom(wt.name, wt.nullable)
        is WireType.Refined   -> Reference.Custom(wt.name, wt.nullable)
    }

    private fun buildRefinedReference(r: WireType.Refined): Reference.Primitive {
        val type: Reference.Primitive.Type = when (r.base.kind) {
            WireType.Primitive.Kind.STRING -> Reference.Primitive.Type.String(
                constraint = r.regex?.let { regExp(it) }
            )
            WireType.Primitive.Kind.INTEGER_32 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.INTEGER_64 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.NUMBER_32 -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.NUMBER_64 -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = buildBoundConstraint(r),
            )
            WireType.Primitive.Kind.BOOLEAN -> Reference.Primitive.Type.Boolean
            WireType.Primitive.Kind.BYTES   -> Reference.Primitive.Type.Bytes
        }
        // A refined type definition can never be nullable; nullability lives at the use site.
        return Reference.Primitive(type, false)
    }

    /**
     * Wrap a bare pattern in a Wirespec regex literal — `/pattern/g`. The Wirespec tokenizer
     * only recognises a regex when it is slash-delimited and ends the pattern at the first
     * unescaped `/`, so inner slashes are escaped here.
     */
    private fun regExp(pattern: String): Reference.Primitive.Type.Constraint.RegExp {
        val body = pattern.replace("\\/", "/").replace("/", "\\/")
        return Reference.Primitive.Type.Constraint.RegExp("/$body/g")
    }

    private fun buildBoundConstraint(r: WireType.Refined): Reference.Primitive.Type.Constraint.Bound? =
        if (r.min != null || r.max != null) Reference.Primitive.Type.Constraint.Bound(r.min, r.max) else null

    private fun primitiveRef(p: WireType.Primitive): Reference.Primitive {
        val type: Reference.Primitive.Type = when (p.kind) {
            WireType.Primitive.Kind.STRING     -> Reference.Primitive.Type.String(constraint = null)
            WireType.Primitive.Kind.INTEGER_32 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = null,
            )
            WireType.Primitive.Kind.INTEGER_64 -> Reference.Primitive.Type.Integer(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = null,
            )
            WireType.Primitive.Kind.NUMBER_32  -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P32,
                constraint = null,
            )
            WireType.Primitive.Kind.NUMBER_64  -> Reference.Primitive.Type.Number(
                precision = Reference.Primitive.Type.Precision.P64,
                constraint = null,
            )
            WireType.Primitive.Kind.BOOLEAN    -> Reference.Primitive.Type.Boolean
            WireType.Primitive.Kind.BYTES      -> Reference.Primitive.Type.Bytes
        }
        return Reference.Primitive(type, p.nullable)
    }

    private fun PathSegment.toWs(): WsEndpoint.Segment = when (this) {
        is PathSegment.Literal  -> WsEndpoint.Segment.Literal(value)
        is PathSegment.Variable -> WsEndpoint.Segment.Param(FieldIdentifier(name), toReference(type))
    }

    private fun WireType.Field.toField(): WsField = WsField(
        annotations = emptyList(),
        identifier = FieldIdentifier(name),
        reference = toReference(type),
    )

    private fun Param.toField(): WsField = WsField(
        annotations = emptyList(),
        identifier = FieldIdentifier(name),
        reference = toReference(type),
    )

    private val Rpc.Kind.annotationName: String get() = "GraphQL$typeName"

    private fun HttpMethod.toWs(): WsEndpoint.Method = when (this) {
        HttpMethod.GET     -> WsEndpoint.Method.GET
        HttpMethod.POST    -> WsEndpoint.Method.POST
        HttpMethod.PUT     -> WsEndpoint.Method.PUT
        HttpMethod.PATCH   -> WsEndpoint.Method.PATCH
        HttpMethod.DELETE  -> WsEndpoint.Method.DELETE
        HttpMethod.OPTIONS -> WsEndpoint.Method.OPTIONS
        HttpMethod.HEAD    -> WsEndpoint.Method.HEAD
        HttpMethod.TRACE   -> WsEndpoint.Method.TRACE
    }
}
