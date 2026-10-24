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
     * An RPC, annotated the way Wirespec's GraphQL converter (wirespec PR #710) does: with its
     * operation type — `@GraphQLQuery`, `@GraphQLMutation`, or `@GraphQLSubscription` — and with
     * `@GraphQLName` when the field name can't be read back from the RPC name.
     */
    fun toRpc(r: Rpc): WsRpc = WsRpc(
        comment = null,
        annotations = listOfNotNull(
            Annotation(
                "GraphQL${r.kind.typeName}",
                listOfNotNull(
                    Annotation.Parameter("default", Annotation.Value.Single(r.rootTypeName))
                        .takeIf { r.rootTypeName != r.kind.typeName },
                ),
            ),
            Annotation("GraphQLName", listOf(Annotation.Parameter("default", Annotation.Value.Single(r.field))))
                .takeIf { r.name.replaceFirstChar(Char::lowercase) != r.field },
        ),
        identifier = DefinitionIdentifier(r.name),
        shape = Shape(r.arguments.map { it.toField() }),
        result = r.result?.let(::toReference) ?: Reference.Unit(false),
        error = null,
    )

    /**
     * A GraphQL object type holding fields resolved by non-root `@SchemaMapping`s. Their
     * arguments are written as `@GraphQLArgument(name: …, type: …)`, the type in GraphQL
     * syntax with input objects under their GraphQL input name from [inputNames].
     */
    fun toGraphQlType(name: String, fields: List<GraphQlField>, inputNames: Map<String, String> = emptyMap()): WsType = WsType(
        comment = null,
        annotations = emptyList(),
        identifier = DefinitionIdentifier(name),
        shape = Shape(
            fields.map { field ->
                WsField(
                    annotations = field.arguments.map { argument ->
                        Annotation(
                            "GraphQLArgument",
                            listOf(
                                Annotation.Parameter("name", Annotation.Value.Single(argument.name)),
                                Annotation.Parameter("type", Annotation.Value.Single(argument.type.toGraphQlType(inputNames))),
                            ),
                        )
                    },
                    identifier = FieldIdentifier(field.field),
                    reference = field.result?.let(::toReference) ?: Reference.Unit(false),
                )
            },
        ),
        extends = emptyList(),
    )

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
            annotations = emptyList(),
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

    /**
     * The GraphQL type of an argument, mapped as Wirespec's GraphQL converter maps a
     * reference: any `Integer` is an `Int` and any `Number` a `Float`, `Bytes` go as a
     * `String`, and `Any` and dictionaries become the `JSON` scalar.
     */
    private fun WireType.toGraphQlType(inputNames: Map<String, String>): String {
        val base = when (this) {
            is WireType.Any -> "JSON"
            is WireType.Primitive -> when (kind) {
                WireType.Primitive.Kind.STRING,
                WireType.Primitive.Kind.BYTES,
                -> "String"
                WireType.Primitive.Kind.INTEGER_32,
                WireType.Primitive.Kind.INTEGER_64,
                -> "Int"
                WireType.Primitive.Kind.NUMBER_32,
                WireType.Primitive.Kind.NUMBER_64,
                -> "Float"
                WireType.Primitive.Kind.BOOLEAN -> "Boolean"
            }
            is WireType.Ref -> inputNames[name] ?: name
            is WireType.ListOf -> "[${element.toGraphQlType(inputNames)}]"
            is WireType.MapOf -> "JSON"
            is WireType.Object -> inputNames[name] ?: name
            is WireType.EnumDef -> name
            is WireType.Refined -> name
        }
        return if (nullable) base else "$base!"
    }

    private fun Param.toField(): WsField = WsField(
        annotations = emptyList(),
        identifier = FieldIdentifier(name),
        reference = toReference(type),
    )

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
