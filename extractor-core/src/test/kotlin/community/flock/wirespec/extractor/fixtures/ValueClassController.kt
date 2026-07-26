package community.flock.wirespec.extractor.fixtures

import community.flock.wirespec.extractor.fixtures.dto.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Value classes in a controller signature. The Kotlin compiler mangles the JVM names of
 * these methods (`getUser-_mjp9w0`), which must not leak into the endpoint identifiers.
 */
@RestController
class ValueClassController {

    @GetMapping("/vc/{id}")
    fun getUser(@PathVariable id: UserId): UserId = id

    @GetMapping("/vc")
    fun listUsers(): ResponseEntity<List<UserId>> = ResponseEntity.ok(emptyList())

    @PostMapping("/vc")
    fun createUser(@RequestBody id: UserId): ResponseEntity<UserId> = ResponseEntity.ok(id)
}
