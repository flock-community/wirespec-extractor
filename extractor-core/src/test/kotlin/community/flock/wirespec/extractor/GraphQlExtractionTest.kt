package community.flock.wirespec.extractor

import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.ModuleContent
import community.flock.wirespec.compiler.core.ParseContext
import community.flock.wirespec.compiler.core.parse
import community.flock.wirespec.compiler.utils.noLogger
import community.flock.wirespec.converter.graphql.GraphQLEmitter
import community.flock.wirespec.extractor.fixtures.graphql.BookController
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GraphQlExtractionTest {

    private fun extract(tmp: Path, extractGraphQl: Boolean = true): List<File> =
        WirespecExtractor.extract(
            ExtractConfig(
                classesDirectories = listOf(File(BookController::class.java.protectionDomain.codeSource.location.toURI())),
                runtimeClasspath = emptyList(),
                outputDirectory = File(tmp.toFile(), "ws").apply { mkdirs() },
                basePackage = "community.flock.wirespec.extractor.fixtures.graphql",
                extractGraphQl = extractGraphQl,
            )
        ).filesWritten

    @Test
    fun `emits GraphQL operations as rpc definitions per controller`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.map { it.name } shouldContainExactlyInAnyOrder listOf(
            "BookController.ws", "LibraryAdminController.ws", "MixedLibraryController.ws",
            "LibraryNamespaceController.ws", "LibraryNamespaceFieldsController.ws", "ReviewController.ws",
        )

        val ws = files.single { it.name == "BookController.ws" }.readText()
        // Annotated as Wirespec's GraphQL converter does: operation type, and @GraphQLName
        // where the field name can't be read back from the rpc name.
        ws shouldContain """
            |@GraphQLQuery
            |rpc BookById {
            |  id: String
            |} -> Book?
        """.trimMargin()
        ws shouldContain "@GraphQLQuery\nrpc BookCount {} -> Integer32"
        ws shouldContain "@GraphQLMutation\nrpc DeleteBook {\n  bookId: String\n} -> Unit"
        ws shouldContain "@GraphQLSubscription\nrpc BookAdded {\n  genre: Genre?\n} -> Book"
        ws shouldContain "@GraphQLQuery\nrpc SearchBooks {"
        ws shouldContain "} -> Book[]"
        // `book` is taken by `type Book`, so the rpc takes the root type as prefix.
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"book\")\nrpc QueryBook {\n  id: String\n} -> Book?"
        ws.split("rpc ").size - 1 shouldBe 10
        ws shouldNotContain "endpoint "

        // Types reached from arguments and results live with the controller.
        ws shouldContain "type Book {"
        ws shouldContain "type BookInput {"
        ws shouldContain "enum Genre {"
        // The @Arguments object itself is not emitted.
        ws shouldNotContain "BookFilter"
        ws shouldContain "author: Author"
        ws shouldContain "type Author {"

        files.single { it.name == "LibraryAdminController.ws" }.readText() shouldContain
            "@GraphQLMutation\nrpc ResetLibrary {} -> Boolean"

        files.single { it.name == "LibraryNamespaceController.ws" }.readText().let { namespace ->
            namespace shouldContain "@GraphQLQuery\nrpc Library {} -> Unit"
            namespace shouldNotContain "type LibraryQuery"
        }

        files.single { it.name == "LibraryNamespaceFieldsController.ws" }.readText().let { namespace ->
            namespace shouldContain """
                |@GraphQLQuery("LibraryQuery")
                |rpc Catalog {
                |  genre: String?
                |} -> LibraryBook[]
            """.trimMargin()
            namespace shouldNotContain "type LibraryQuery"
        }

        // The annotated output parses back with Wirespec.
        parse(files).isRight() shouldBe true
    }

    @Test
    fun `input objects are marked @GraphQLInput as the GraphQL converter writes them`(@TempDir tmp: Path) {
        val files = extract(tmp)
        val books = files.single { it.name == "BookController.ws" }.readText()
        val reviews = files.single { it.name == "ReviewController.ws" }.readText()

        // Only ever taken: an input object.
        books shouldContain "@GraphQLInput\ntype BookInput {"
        // Returned: an object type.
        books shouldContain "\ntype Book {"
        books shouldNotContain "@GraphQLInput\ntype Book {"
        // Taken and returned: left unmarked, so the converter writes it twice.
        reviews shouldContain "\ntype Shelf {"
        reviews shouldNotContain "@GraphQLInput"
    }

    @Test
    fun `field resolver arguments carry GraphQL types as the GraphQL converter reads them`(@TempDir tmp: Path) {
        val books = extract(tmp).single { it.name == "BookController.ws" }.readText()

        books shouldContain """
            |  @GraphQLArgument(name: "first", type: "Int!")
            |  @GraphQLArgument(name: "filter", type: "ReviewFilter")
            |  @GraphQLArgument(name: "shelf", type: "ShelfInput")
            |  reviews: Review[]
        """.trimMargin()
        // The argument's input type lives next to the type that takes it.
        books shouldContain "@GraphQLInput\ntype ReviewFilter {"
    }

    @Test
    fun `extracted operations convert to a GraphQL schema with Wirespec's GraphQL converter`(@TempDir tmp: Path) {
        val ast = parse(extract(tmp)).getOrNull()!!
        val schema = GraphQLEmitter.emit(ast, noLogger).head.result

        // Root fields, under their GraphQL field names: `QueryBook` reads back as `book`.
        schema shouldContain "type Query {\n  book(\n    id: String!\n  ): Book\n  bookById("
        schema shouldContain "  addBook(\n    input: BookInput!\n  ): Book!"
        schema shouldContain "type Subscription {\n  bookAdded(\n    genre: Genre\n  ): Book!\n}"
        // Input objects: only taken, or a copy of a type that is also returned.
        schema shouldContain "input BookInput {"
        schema shouldContain "input ReviewFilter {"
        schema shouldContain "type Shelf {"
        schema shouldContain "input ShelfInput {"
        schema shouldContain "  moveShelf(\n    shelf: ShelfInput!\n  ): Boolean!"
        // A field resolver with its arguments.
        schema shouldContain "  reviews(\n    first: Int!\n    filter: ReviewFilter\n    shelf: ShelfInput\n  ): [Review!]!"
    }

    private fun parse(files: List<File>) = object : ParseContext {
        override val logger = noLogger
    }.parse(files.map { ModuleContent(FileUri(it.name), it.readText()) }.toNonEmptyListOrNull()!!)

    @Test
    fun `a controller with both REST endpoints and GraphQL operations gets both in one file`(@TempDir tmp: Path) {
        val ws = extract(tmp).single { it.name == "MixedLibraryController.ws" }.readText()
        ws shouldContain "endpoint RestCount GET /books/count"
        ws shouldContain "@GraphQLQuery\nrpc ShelfCount {} -> Integer32"
    }

    @Test
    fun `extractGraphQl=false skips GraphQL controllers`(@TempDir tmp: Path) {
        val files = extract(tmp, extractGraphQl = false)
        files.map { it.name } shouldBe listOf("MixedLibraryController.ws")
        files.single().readText() shouldNotContain "rpc "
    }
}
