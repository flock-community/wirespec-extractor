package community.flock.wirespec.extractor

import arrow.core.toNonEmptyListOrNull
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.ModuleContent
import community.flock.wirespec.compiler.core.ParseContext
import community.flock.wirespec.compiler.core.parse
import community.flock.wirespec.compiler.utils.noLogger
import community.flock.wirespec.compiler.core.parse.ast.Rpc
import community.flock.wirespec.converter.graphql.GraphQLEmitter
import community.flock.wirespec.converter.graphql.GraphQLOperations
import community.flock.wirespec.extractor.fixtures.graphql.BookController
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
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
        // LibraryNamespaceController only opens a namespace, which is no rpc: it has no file.
        files.map { it.name } shouldContainExactlyInAnyOrder listOf(
            "BookController.ws", "LibraryAdminController.ws", "MixedLibraryController.ws",
            "LibraryNamespaceFieldsController.ws", "ReviewController.ws",
        )

        val ws = files.single { it.name == "BookController.ws" }.readText()
        // Annotated as Wirespec's GraphQL converter does: operation type, and @GraphQLName
        // always, so the original field name is never lost.
        ws shouldContain """
            |@GraphQLQuery
            |@GraphQLName("bookById")
            |rpc BookById {
            |  id: String
            |} -> Book?
        """.trimMargin()
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"bookCount\")\nrpc BookCount {} -> Integer32"
        ws shouldContain "@GraphQLMutation\n@GraphQLName(\"deleteBook\")\nrpc DeleteBook {\n  bookId: String\n} -> Unit"
        ws shouldContain "@GraphQLSubscription\n@GraphQLName(\"bookAdded\")\nrpc BookAdded {\n  genre: Genre?\n} -> Book"
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"searchBooks\")\nrpc SearchBooks {"
        ws shouldContain "} -> Book[]"
        // `book` is taken by `type Book`, so the rpc takes the root type as prefix.
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"book\")\nrpc QueryBook {\n  id: String\n} -> Book?"
        ws.split("rpc ").size - 1 shouldBe 11
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
            "@GraphQLMutation\n@GraphQLName(\"resetLibrary\")\nrpc ResetLibrary {} -> Boolean"

        files.single { it.name == "LibraryNamespaceFieldsController.ws" }.readText().let { namespace ->
            // The field that opens the namespace is in the file header, and the namespace has no type.
            namespace shouldContain """
                |@file:GraphQLQuery("Query", path: { library: "LibraryQuery!" })
                |
                |@GraphQLQuery("LibraryQuery")
                |@GraphQLName("catalog")
                |rpc Catalog {
                |  genre: String?
                |} -> LibraryBook[]
            """.trimMargin()
            namespace shouldNotContain "type LibraryQuery"
            namespace shouldNotContain "rpc Library "
        }

        // The annotated output parses back with Wirespec.
        parse(files).isRight() shouldBe true
    }

    @Test
    fun `UUID arguments are the UUID scalar`(@TempDir tmp: Path) {
        val files = extract(tmp)
        files.single { it.name == "BookController.ws" }.readText() shouldContain "uuid: UUID"
        GraphQLEmitter.emit(parse(files).getOrNull()!!, noLogger).head.result shouldContain "bookByUuid(\n    uuid: UUID!\n  )"
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
        reviews shouldNotContain "@GraphQLInput\ntype Shelf {"
        // Only taken, by a field with arguments.
        reviews shouldContain "@GraphQLInput\ntype ReviewFilter {"
    }

    @Test
    fun `a field resolver with arguments is an rpc that names its type`(@TempDir tmp: Path) {
        val files = extract(tmp)
        val reviews = files.single { it.name == "ReviewController.ws" }.readText()

        reviews shouldContain """
            |@GraphQLQuery("Book")
            |@GraphQLName("reviews")
            |rpc BookReviews {
            |  first: Integer,
            |  filter: ReviewFilter?,
            |  shelf: Shelf?
            |} -> Review[]
        """.trimMargin()
        // The argument's input type lives with the rpc that takes it.
        reviews shouldContain "@GraphQLInput\ntype ReviewFilter {"
        // The type keeps its fields without arguments.
        files.single { it.name == "BookController.ws" }.readText() shouldContain
            "type Book {\n  id: String,\n  title: String,\n  genre: Genre,\n  author: Author\n}"
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
        // A field resolver with its arguments, after the fields of its type.
        schema shouldContain "  author: Author!\n  reviews(\n    first: Int!\n    filter: ReviewFilter\n    shelf: ShelfInput\n  ): [Review!]!\n}"
        // A namespace, nested in the root type by the field the file header names.
        schema shouldContain "  library: LibraryQuery!\n}"
        schema shouldContain "type LibraryQuery {\n  catalog(\n    genre: String\n  ): [LibraryBook!]!\n}"
    }

    @Test
    fun `the GraphQL client queries a namespace through the field that leads to it`(@TempDir tmp: Path) {
        val ast = parse(extract(tmp)).getOrNull()!!
        val operations = GraphQLOperations(ast)
        fun rpc(name: String) = ast.modules.flatMap { it.statements }.filterIsInstance<Rpc>().single { it.identifier.value == name }

        operations.document(rpc("Catalog"), "result") shouldBe
            "query Catalog(\$genre: String) { library { result: catalog(genre: \$genre) { title } } }"
        // A field of a type that holds data is no operation of its own.
        operations.document(rpc("BookReviews"), "result").shouldBeNull()
    }

    private fun parse(files: List<File>) = object : ParseContext {
        override val logger = noLogger
    }.parse(files.map { ModuleContent(FileUri(it.name), it.readText()) }.toNonEmptyListOrNull()!!)

    @Test
    fun `a controller with both REST endpoints and GraphQL operations gets both in one file`(@TempDir tmp: Path) {
        val ws = extract(tmp).single { it.name == "MixedLibraryController.ws" }.readText()
        ws shouldContain "endpoint RestCount GET /books/count"
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"shelfCount\")\nrpc ShelfCount {} -> Integer32"
    }

    @Test
    fun `extractGraphQl=false skips GraphQL controllers`(@TempDir tmp: Path) {
        val files = extract(tmp, extractGraphQl = false)
        files.map { it.name } shouldBe listOf("MixedLibraryController.ws")
        files.single().readText() shouldNotContain "rpc "
    }
}
