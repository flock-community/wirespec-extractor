package com.acme.api

import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Controller
class BookController {

    @QueryMapping
    fun bookById(@Argument id: String): Book? = null

    @QueryMapping
    fun books(@Argument genre: Genre?): List<Book> = emptyList()

    @MutationMapping
    fun addBook(@Argument input: BookInput): Mono<Book> = Mono.empty()

    @SubscriptionMapping
    fun bookAdded(): Flux<Book> = Flux.empty()

    // Field resolver on a non-root type: not an operation, so not an rpc.
    @SchemaMapping(typeName = "Book")
    fun author(book: Book): Author = Author("anonymous")
}
