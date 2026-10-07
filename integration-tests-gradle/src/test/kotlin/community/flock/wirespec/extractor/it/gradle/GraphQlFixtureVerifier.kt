package community.flock.wirespec.extractor.it.gradle

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Verifier for the `graphql-app` fixture (Gradle: reads `build/wirespec`). Asserts that Spring for GraphQL
 * queries, mutations, and subscriptions become `rpc` definitions — annotated with
 * their operation type — in the controller's .ws file, that input types state
 * their Kotlin constructor defaults, and that field resolvers on non-root types
 * are not extracted.
 */
object GraphQlFixtureVerifier {

    fun verify(wsDir: File) {
        assertTrue(wsDir.isDirectory) { "wirespec output dir missing at ${wsDir.absolutePath}" }

        wsDir.listFiles()!!.map { it.name }.sorted() shouldContainExactly listOf("BookController.ws")

        val ws = File(wsDir, "BookController.ws").readText()
        ws shouldContain "@Query\nrpc BookById {\n  id: String\n} -> Book?"
        ws shouldContain "@Query\nrpc Books {\n  genre: Genre?\n} -> Book[]"
        ws shouldContain "@Mutation\nrpc AddBook {\n  input: BookInput\n} -> Book"
        ws shouldContain "@Subscription\nrpc BookAdded {} -> Book"
        ws shouldContain "type Book {"
        // Kotlin constructor defaults of a GraphQL input type are stated as annotations.
        ws shouldContain "type BookInput {\n  title: String,\n  @Default(FICTION) genre: Genre,\n  @Default(100) pages: Integer32\n}"
        ws shouldContain "enum Genre {"
        ws shouldNotContain "Author"
        ws shouldNotContain "endpoint "
    }
}
