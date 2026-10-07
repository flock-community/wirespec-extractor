package community.flock.wirespec.extractor.fixtures.graphql

import org.springframework.graphql.data.ArgumentValue
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.Arguments
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.security.Principal

enum class Genre { FICTION, SCIENCE }

data class Author(val name: String)

data class Book(val id: String, val title: String, val genre: Genre)

data class BookInput(val title: String, val genre: Genre, val authorName: String?)

data class BookFilter(val titleContains: String?, val limit: Int)

@Suppress("unused", "UNUSED_PARAMETER")
@Controller
class BookController {

    @QueryMapping
    fun bookById(@Argument id: String): Book? = null

    /** Named like `type Book`: the rpc takes the root type as prefix, `QueryBook`. */
    @QueryMapping
    fun book(@Argument id: String): Book? = null

    @QueryMapping
    fun books(@Argument genre: Genre?): List<Book> = emptyList()

    /** Field name from the annotation; arguments bound as one object. */
    @QueryMapping("searchBooks")
    fun search(@Arguments filter: BookFilter): Flux<Book> = Flux.empty()

    /** Non-argument parameters (the principal) contribute nothing. */
    @MutationMapping
    fun addBook(@Argument input: BookInput, principal: Principal?): Mono<Book> = Mono.empty()

    @MutationMapping
    fun deleteBook(@Argument("bookId") id: String) {}

    @MutationMapping
    fun renameBook(@Argument id: String, @Argument title: ArgumentValue<String>): Book = TODO()

    @SubscriptionMapping
    fun bookAdded(@Argument genre: Genre?): Flux<Book> = Flux.empty()

    /** Plain @SchemaMapping on a root type, with an explicit field name. */
    @SchemaMapping(typeName = "Query", field = "bookCount")
    fun countBooks(): Int = 0

    @QueryMapping
    suspend fun latestBook(): Book = TODO()

    /** A field resolver on a non-root type is not an operation. */
    @SchemaMapping(typeName = "Book")
    fun author(book: Book): Author = Author("anonymous")
}

/** Class-level @SchemaMapping supplies the type name for every handler. */
@Suppress("unused")
@Controller
@SchemaMapping(typeName = "Mutation")
class LibraryAdminController {

    @SchemaMapping
    fun resetLibrary(): Boolean = true
}

/** One @Controller serving both a @ResponseBody endpoint and a GraphQL query. */
@Suppress("unused")
@Controller
class MixedLibraryController {

    @GetMapping("/books/count")
    @ResponseBody
    fun restCount(): Int = 0

    @QueryMapping
    fun shelfCount(): Int = 0
}

@Suppress("unused", "UNUSED_PARAMETER")
@Controller
class LibraryNamespaceController {

    @QueryMapping
    fun library(): Namespace = Namespace

    object Namespace
}

data class LibraryBook(val title: String)

@Suppress("unused", "UNUSED_PARAMETER")
@Controller
class LibraryNamespaceFieldsController {

    @SchemaMapping(typeName = "LibraryQuery")
    fun catalog(@Argument genre: String?): List<LibraryBook> = emptyList()
}
