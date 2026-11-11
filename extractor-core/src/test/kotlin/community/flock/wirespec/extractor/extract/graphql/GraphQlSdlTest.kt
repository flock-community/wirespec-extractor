package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.extract.graphql.GraphQlSdl.TypeRef.ListOf
import community.flock.wirespec.extractor.extract.graphql.GraphQlSdl.TypeRef.Named
import community.flock.wirespec.extractor.extract.graphql.GraphQlSdl.TypeRef.NonNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GraphQlSdlTest {

    @Test
    fun `reads field and argument types of types, interfaces, inputs, and extensions`() {
        val definitions = GraphQlSdl.parse(
            """
            ""${'"'}
            The root. ""${'"'} still a description ""${'"'}
            ""${'"'}
            type Query {
              "One node, by id"
              node(id: ID! @deprecated(reason: "use nodes"), kind: Kind = BOOK): Node
              nodes(ids: [ID!]!, filter: Filter = { tags: ["a", "b"], min: -1.5e3 }): [Node]!
            }

            interface Node @key(fields: "id") { id: ID! }

            type Book implements Node & Named @shareable {
              id: ID!, # a comment, and commas are insignificant
              name: String
            }

            input Filter { tags: [String!] = [] , min: Float }

            extend type Query { book(isbn: ID!): Book }
            extend type Book @extra
            """.trimIndent()
        )

        definitions.map { it.name } shouldContainExactly listOf("Query", "Node", "Book", "Filter", "Query", "Book")
        val query = definitions.first()
        query.fields.map { it.name } shouldContainExactly listOf("node", "nodes")
        query.fields[0].arguments shouldBe mapOf("id" to NonNull(Named("ID")), "kind" to Named("Kind"))
        query.fields[0].type shouldBe Named("Node")
        query.fields[1].arguments["ids"] shouldBe NonNull(ListOf(NonNull(Named("ID"))))
        query.fields[1].type shouldBe NonNull(ListOf(Named("Node")))
        definitions[2].fields.map { it.name to it.type } shouldContainExactly listOf(
            "id" to NonNull(Named("ID")),
            "name" to Named("String"),
        )
        definitions[3].fields.map { it.name } shouldContainExactly listOf("tags", "min")
        definitions[4].fields.single().arguments shouldBe mapOf("isbn" to NonNull(Named("ID")))
        definitions[5].fields shouldBe emptyList()
    }

    @Test
    fun `skips everything that declares no fields`() {
        val definitions = GraphQlSdl.parse(
            """
            schema { query: Query }
            scalar DateTime @specifiedBy(url: "https://example.com")
            enum Kind { BOOK "a magazine" MAGAZINE }
            union Result = Book | Magazine
            directive @key(fields: String!) repeatable on OBJECT | INTERFACE
            extend schema @link(url: "https://example.com", import: ["@key"])
            query Books(${'$'}first: Int = 10) { books(first: ${'$'}first) { ...BookFields } }
            fragment BookFields on Book { id }
            { me { id } }
            type Magazine { issue: ID }
            """.trimIndent()
        )

        definitions.map { it.name } shouldContainExactly listOf("Magazine")
        definitions.single().fields.single().type shouldBe Named("ID")
    }

    @Test
    fun `rejects malformed input`() {
        shouldThrow<GraphQlSdl.SyntaxException> { GraphQlSdl.parse("type Query { book(id: ID!: Book }") }
        shouldThrow<GraphQlSdl.SyntaxException> { GraphQlSdl.parse("type Query { title: \"unterminated }") }
    }
}
