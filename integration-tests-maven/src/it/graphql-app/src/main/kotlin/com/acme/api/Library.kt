package com.acme.api

enum class Genre { FICTION, SCIENCE }

data class Author(val name: String)

data class Book(val id: String, val title: String, val genre: Genre)

data class BookInput(val title: String, val genre: Genre)
