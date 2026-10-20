package community.flock.wirespec.extractor.fixtures.graphql

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

data class Review(val stars: Int, val text: String)

/** Only ever taken, as a field argument: a GraphQL `input`. */
data class ReviewFilter(val minStars: Int)

/** Taken by a mutation and returned by a query: an object type with a `ShelfInput` copy. */
data class Shelf(val name: String)

@Suppress("unused", "UNUSED_PARAMETER")
@Controller
class ReviewController {

    /** A field of `Book` with arguments, kept as `@GraphQLArgument`s on the field. */
    @SchemaMapping(typeName = "Book")
    fun reviews(book: Book, @Argument first: Long, @Argument filter: ReviewFilter?, @Argument shelf: Shelf?): List<Review> =
        emptyList()

    @QueryMapping
    fun shelf(): Shelf = Shelf("new")

    @MutationMapping
    fun moveShelf(@Argument shelf: Shelf): Boolean = true
}
