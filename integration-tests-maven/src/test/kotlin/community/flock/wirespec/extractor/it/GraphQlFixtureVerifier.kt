package community.flock.wirespec.extractor.it

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `graphql-app` fixture (Maven: reads `target/wirespec`). Asserts that Spring for GraphQL
 * queries, mutations, and subscriptions become `rpc` definitions in the
 * controller's .ws file — annotated and named as Wirespec's GraphQL converter
 * does — and that field resolvers on non-root types do not.
 */
object GraphQlFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        wsDir.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf("BookController.ws")

        val ws = File(wsDir, "BookController.ws").readText()
        ws shouldContain "@GraphQLQuery\nrpc BookById {\n  id: String\n} -> Book?"
        ws shouldContain "@GraphQLQuery\nrpc Books {\n  genre: Genre?\n} -> Book[]"
        ws shouldContain "@GraphQLMutation\nrpc AddBook {\n  input: BookInput\n} -> Book"
        ws shouldContain "@GraphQLSubscription\nrpc BookAdded {} -> Book"
        // `book` is taken by `type Book`: prefixed with the root type, field name kept.
        ws shouldContain "@GraphQLQuery\n@GraphQLName(\"book\")\nrpc QueryBook {\n  id: String\n} -> Book?"
        ws shouldContain "type Book {"
        ws shouldContain "type BookInput {"
        ws shouldContain "enum Genre {"
        ws shouldNotContain "Author"
        ws shouldNotContain "endpoint "
    }
}
