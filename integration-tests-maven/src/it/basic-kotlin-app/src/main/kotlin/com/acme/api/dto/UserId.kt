package com.acme.api.dto

/**
 * Kotlin value class. It must be flattened to its underlying value everywhere it is
 * used — no `UserId` wrapper type in the generated Wirespec — and the `-<hash>` the
 * compiler appends to method names using it must not reach the endpoint identifiers.
 */
@JvmInline
value class UserId(val value: String)
