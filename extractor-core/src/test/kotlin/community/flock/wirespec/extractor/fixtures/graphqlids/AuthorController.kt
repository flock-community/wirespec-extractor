package community.flock.wirespec.extractor.fixtures.graphqlids

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

// Shaped like Netflix DGS codegen output for src/test/resources/schema/*.graphqls:
// every `ID` is a plain String (or a Long, through a type mapping).
data class Author(val id: String, val legacyId: Long?, val name: String)

data class AuthorInput(val name: String, val mentorId: String?)

data class Book(val isbn: String, val title: String)

@Controller
class AuthorController {

    @QueryMapping
    fun author(@Argument id: String): Author? = null

    @QueryMapping
    fun authors(@Argument ids: List<String>): List<Author> = emptyList()

    @QueryMapping
    fun authorByName(@Argument name: String): Author? = null

    @MutationMapping
    fun addAuthor(@Argument input: AuthorInput): String = ""

    @SchemaMapping(typeName = "Author")
    fun books(author: Author, @Argument after: String?, @Argument first: Int?): List<Book> = emptyList()
}
