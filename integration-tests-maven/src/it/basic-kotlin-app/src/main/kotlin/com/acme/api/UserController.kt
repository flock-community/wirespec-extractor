package com.acme.api

import com.acme.api.dto.UserDto
import com.acme.api.dto.UserId
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController {

    @GetMapping("/{id}")
    fun getUser(@PathVariable id: String): UserDto = throw NotImplementedError()

    @PostMapping
    fun createUser(@RequestBody body: UserDto): UserDto = body

    // Coroutines: a Kotlin suspend function compiles to a Java method with a
    // trailing `Continuation<? super T>` parameter and an erased `Object` return.
    // The extractor must recover T (here: List<UserDto>) and not leak Continuation
    // / CoroutineContext into the schema.
    @GetMapping
    suspend fun listUsers(): List<UserDto> = throw NotImplementedError()

    @DeleteMapping("/{id}")
    suspend fun deleteUser(@PathVariable id: String) { /* Unit-returning suspend → 204 */ }

    @GetMapping("/page")
    fun page(): com.acme.api.dto.Page<UserDto> = throw NotImplementedError()

    // Value classes: `UserId` must flatten to its underlying String — no `UserId` type in
    // the generated Wirespec. A value-class parameter also makes the compiler mangle the
    // JVM method name (`findByUserId-<hash>`), which must not leak into the endpoint name.
    @GetMapping("/by-user-id/{id}")
    fun findByUserId(@PathVariable id: UserId): List<UserId> = throw NotImplementedError()
}
