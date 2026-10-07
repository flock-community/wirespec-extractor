package community.flock.wirespec.extractor.extract.graphql

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.fixtures.graphql.BookController
import community.flock.wirespec.extractor.fixtures.graphql.LibraryAdminController
import community.flock.wirespec.extractor.model.Rpc
import community.flock.wirespec.extractor.model.WireType
import community.flock.wirespec.extractor.model.WireType.Primitive.Kind
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GraphQlRpcExtractorTest {

    private val types = TypeExtractor()
    private val rpcs = GraphQlRpcExtractor(types).extract(BookController::class.java).associateBy { it.name }

    private fun rpc(name: String): Rpc = rpcs.getValue(name)

    private val string = WireType.Primitive(Kind.STRING)
    private val book = WireType.Ref("Book")

    @Test
    fun `discovers queries mutations and subscriptions but not field resolvers`() {
        rpcs.keys shouldContainExactlyInAnyOrder listOf(
            "BookById", "Book", "Books", "SearchBooks", "BookCount", "LatestBook",
            "AddBook", "DeleteBook", "RenameBook",
            "BookAdded",
        )
    }

    @Test
    fun `each rpc knows its GraphQL field and operation type`() {
        rpc("BookById").field shouldBe "bookById"
        rpc("BookCount").field shouldBe "bookCount"
        rpc("SearchBooks").field shouldBe "searchBooks"
        rpc("BookById").kind shouldBe Rpc.Kind.QUERY
        rpc("BookCount").kind shouldBe Rpc.Kind.QUERY
        rpc("AddBook").kind shouldBe Rpc.Kind.MUTATION
        rpc("BookAdded").kind shouldBe Rpc.Kind.SUBSCRIPTION
    }

    @Test
    fun `argument names and types come from @Argument parameters`() {
        rpc("BookById").arguments shouldBe listOf(WireType.Field("id", string))
        rpc("Books").arguments shouldBe listOf(WireType.Field("genre", WireType.Ref("Genre", nullable = true)))
        rpc("DeleteBook").arguments shouldBe listOf(WireType.Field("bookId", string))
    }

    @Test
    fun `non-argument parameters are ignored`() {
        rpc("AddBook").arguments shouldBe listOf(WireType.Field("input", WireType.Ref("BookInput")))
        rpc("BookCount").arguments shouldBe emptyList()
    }

    @Test
    fun `ArgumentValue is unwrapped to a nullable argument`() {
        rpc("RenameBook").arguments shouldBe listOf(
            WireType.Field("id", string),
            WireType.Field("title", string.copy(nullable = true)),
        )
    }

    @Test
    fun `@Arguments object is spread into the argument list without its own definition`() {
        rpc("SearchBooks").arguments shouldContainExactlyInAnyOrder listOf(
            WireType.Field("titleContains", string.copy(nullable = true)),
            WireType.Field("limit", WireType.Primitive(Kind.INTEGER_32)),
        )
        types.definitions.filterIsInstance<WireType.Object>().map { it.name } shouldNotContain "BookFilter"
    }

    @Test
    fun `results follow the declared return type and nullability`() {
        rpc("BookById").result shouldBe book.copy(nullable = true)
        rpc("Books").result shouldBe WireType.ListOf(book)
        rpc("BookCount").result shouldBe WireType.Primitive(Kind.INTEGER_32)
        rpc("DeleteBook").result shouldBe null
    }

    @Test
    fun `reactive and suspend wrappers are unwrapped`() {
        rpc("AddBook").result shouldBe book
        rpc("SearchBooks").result shouldBe WireType.ListOf(book)
        rpc("LatestBook").result shouldBe book
    }

    @Test
    fun `a subscription's result is one event of its stream`() {
        rpc("BookAdded").result shouldBe book
    }

    @Test
    fun `class-level @SchemaMapping supplies the root type name`() {
        val admin = GraphQlRpcExtractor(types).extract(LibraryAdminController::class.java)
        admin.map { it.name } shouldBe listOf("ResetLibrary")
        admin.single().result shouldBe WireType.Primitive(Kind.BOOLEAN)
    }
}
