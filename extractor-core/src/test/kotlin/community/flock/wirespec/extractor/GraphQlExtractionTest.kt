package community.flock.wirespec.extractor

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
            |rpc BookById {
            |  id: String
            |} -> Book?
        """.trimMargin()
        ws shouldContain "rpc BookCount {} -> Integer32"
        ws shouldContain "rpc DeleteBook {\n  bookId: String\n} -> Unit"
        ws shouldContain "rpc BookAdded {\n  genre: Genre?\n} -> Book"
        ws shouldContain "rpc SearchBooks {"
        ws shouldContain "} -> Book[]"
        ws.split("rpc ").size - 1 shouldBe 9
        ws shouldNotContain "endpoint "

        // Types reached from arguments and results live with the controller.
        ws shouldContain "type Book {"
        ws shouldContain "type BookInput {"
        ws shouldContain "enum Genre {"
        // Neither the @Arguments object nor a field resolver's result is emitted.
        ws shouldNotContain "BookFilter"
        ws shouldNotContain "Author"

        files.single { it.name == "LibraryAdminController.ws" }.readText() shouldContain
            "rpc ResetLibrary {} -> Boolean"
    }

    @Test
    fun `a controller with both REST endpoints and GraphQL operations gets both in one file`(@TempDir tmp: Path) {
        val ws = extract(tmp).single { it.name == "MixedLibraryController.ws" }.readText()
        ws shouldContain "endpoint RestCount GET /books/count"
        ws shouldContain "rpc ShelfCount {} -> Integer32"
    }

    @Test
    fun `extractGraphQl=false skips GraphQL controllers`(@TempDir tmp: Path) {
        val files = extract(tmp, extractGraphQl = false)
        files.map { it.name } shouldBe listOf("MixedLibraryController.ws")
        files.single().readText() shouldNotContain "rpc "
    }
}
