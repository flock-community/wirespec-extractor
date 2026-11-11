package community.flock.wirespec.extractor

import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.ModuleContent
import community.flock.wirespec.compiler.core.ParseContext
import community.flock.wirespec.compiler.core.parse
import community.flock.wirespec.compiler.utils.noLogger
import community.flock.wirespec.converter.graphql.GraphQLEmitter
import community.flock.wirespec.extractor.fixtures.graphqlids.AuthorController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GraphQlSchemaIdsTest {

    /** The test resources root, whose `schema/` directory holds the GraphQL schema files. */
    private val resources = File(javaClass.classLoader.getResource("schema/authors.graphqls")!!.toURI()).parentFile.parentFile

    private fun extract(tmp: Path, withSchema: Boolean = true): String =
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(File(AuthorController::class.java.protectionDomain.codeSource.location.toURI())),
                runtimeClasspath = if (withSchema) listOf(resources) else emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.extractor.fixtures.graphqlids",
            )
        ).filesWritten.single().readText()

    @Test
    fun `fields the schema types as ID are the ID scalar`(@TempDir tmp: Path) {
        val ws = extract(tmp)

        // `type Author` takes the name `Author`, so the rpc is `QueryAuthor`.
        ws shouldContain "rpc QueryAuthor {\n  id: ID\n} -> Author?"
        ws shouldContain "rpc Authors {\n  ids: ID[]\n} -> Author[]"
        ws shouldContain "rpc AuthorByName {\n  name: String\n} -> Author?"
        ws shouldContain "rpc AddAuthor {\n  input: AuthorInput\n} -> ID"
        // Object and input fields, an `ID` held in a Long, and nullability as extracted.
        ws shouldContain "type Author {\n  id: ID,\n  legacyId: ID?,\n  name: String"
        ws shouldContain "type AuthorInput {\n  name: String,\n  mentorId: ID?"
        ws shouldContain "type Book {\n  isbn: ID,\n  title: String"
        // A field resolver's arguments, from a type extension.
        ws shouldContain "@GraphQLArgument(name: \"after\", type: \"ID\")"
        ws shouldContain "@GraphQLArgument(name: \"first\", type: \"Int\")"
        // Declared once, as Wirespec's GraphQL converter reads the built-in back.
        ws shouldContain "@GraphQLBuiltIn\ntype ID = String"
    }

    @Test
    fun `the GraphQL schema written back declares ID arguments and fields`(@TempDir tmp: Path) {
        val ws = extract(tmp)
        val ast = object : ParseContext {
            override val logger = noLogger
        }.parse(listOf(ModuleContent(FileUri("AuthorController.ws"), ws)).toNonEmptyListOrNull()!!).getOrNull()!!
        val schema = GraphQLEmitter.emit(ast, noLogger).joinToString("\n") { it.result }

        schema shouldContain "author(\n    id: ID!\n  ): Author"
        schema shouldContain "authors(\n    ids: [ID!]!\n  ): [Author!]!"
        schema shouldContain "addAuthor(\n    input: AuthorInput!\n  ): ID!"
        schema shouldContain "  id: ID!\n"
        schema shouldContain "  mentorId: ID\n"
        schema shouldContain "books(\n    after: ID\n    first: Int\n  ): [Book!]!"
        // A built-in: referenced, never declared.
        schema shouldNotContain "scalar ID"
    }

    @Test
    fun `without a schema an ID stays a String`(@TempDir tmp: Path) {
        val ws = extract(tmp, withSchema = false)
        ws shouldContain "rpc QueryAuthor {\n  id: String\n} -> Author?"
        ws shouldNotContain "ID"
        ("GraphQLBuiltIn" in ws) shouldBe false
    }
}
