package community.flock.wirespec.extractor

import arrow.core.nonEmptyListOf
import community.flock.wirespec.compiler.core.FileUri
import community.flock.wirespec.compiler.core.ModuleContent
import community.flock.wirespec.compiler.core.ParseContext
import community.flock.wirespec.compiler.core.parse
import community.flock.wirespec.compiler.utils.noLogger
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
        )

        val ws = files.single { it.name == "BookController.ws" }.readText()
        ws shouldContain """
            |@Query
            |rpc BookById {
            |  id: String
            |} -> Book?
        """.trimMargin()
        ws shouldContain "@Query\nrpc BookCount {} -> Integer32"
        ws shouldContain "@Mutation\nrpc DeleteBook {\n  bookId: String\n} -> Unit"
        ws shouldContain "@Subscription\nrpc BookAdded {\n  genre: Genre?\n} -> Book"
        ws shouldContain "@Query\nrpc SearchBooks {\n  titleContains: String?,\n  @Default(20) limit: Integer32\n} -> Book[]"
        ws.split("rpc ").size - 1 shouldBe 9
        ws shouldNotContain "endpoint "

        // Types reached from arguments and results live with the controller.
        ws shouldContain "type Book {"
        // GraphQL input types state their Kotlin constructor defaults.
        ws shouldContain """
            |type BookInput {
            |  title: String,
            |  @Default(FICTION) genre: Genre,
            |  authorName: String?,
            |  @Default(100) pages: Integer32,
            |  @Default(4.5) rating: Number,
            |  @Default(true) inPrint: Boolean,
            |  @Default("hardcover") format: String,
            |  series: SeriesInput?,
            |  tags: String[]
            |}
        """.trimMargin()
        ws shouldContain "@Default(1) position: Integer32"
        // An output type's constructor default is not stated.
        ws shouldContain "edition: Integer32"
        ws shouldNotContain "@Default(1) edition"
        ws shouldContain "enum Genre {"
        // Neither the @Arguments object nor a field resolver's result is emitted.
        ws shouldNotContain "BookFilter"
        ws shouldNotContain "Author"

        files.single { it.name == "LibraryAdminController.ws" }.readText() shouldContain
            "@Mutation\nrpc ResetLibrary {} -> Boolean"

        // The annotated output parses back with Wirespec 0.21.
        val ctx = object : ParseContext {
            override val logger = noLogger
        }
        ctx.parse(nonEmptyListOf(ModuleContent(FileUri("BookController.ws"), ws))).isRight() shouldBe true
    }

    @Test
    fun `a controller with both REST endpoints and GraphQL operations gets both in one file`(@TempDir tmp: Path) {
        val ws = extract(tmp).single { it.name == "MixedLibraryController.ws" }.readText()
        ws shouldContain "endpoint RestCount GET /books/count"
        ws shouldContain "@Query\nrpc ShelfCount {} -> Integer32"
    }

    @Test
    fun `extractGraphQl=false skips GraphQL controllers`(@TempDir tmp: Path) {
        val files = extract(tmp, extractGraphQl = false)
        files.map { it.name } shouldBe listOf("MixedLibraryController.ws")
        files.single().readText() shouldNotContain "rpc "
    }
}
