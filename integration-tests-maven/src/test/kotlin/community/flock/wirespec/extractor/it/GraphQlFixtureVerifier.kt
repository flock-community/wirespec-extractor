package community.flock.wirespec.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `graphql-app` fixture (Maven: reads `target/wirespec`). Asserts that Spring for GraphQL
 * queries, mutations, and subscriptions become `rpc` definitions in the
 * controller's .ws file, and that field resolvers on non-root types do not.
 */
object GraphQlFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        wsDir.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf("BookController.ws")

        val ws = File(wsDir, "BookController.ws").readText()
        ws shouldContain "rpc BookById {\n  id: String\n} -> Book?"
        ws shouldContain "rpc Books {\n  genre: Genre?\n} -> Book[]"
        ws shouldContain "rpc AddBook {\n  input: BookInput\n} -> Book"
        ws shouldContain "rpc BookAdded {} -> Book"
        ws shouldContain "type Book {"
        ws shouldContain "type BookInput {"
        ws shouldContain "enum Genre {"
        ws shouldNotContain "Author"
        ws shouldNotContain "endpoint "
    }
}
