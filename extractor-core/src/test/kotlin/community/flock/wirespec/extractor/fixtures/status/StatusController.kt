// src/test/kotlin/community/flock/wirespec/extractor/fixtures/status/StatusController.kt
package community.flock.wirespec.extractor.fixtures.status

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class NewCampaign(val name: String)
data class Campaign(val id: String)

/**
 * Handlers that pick their HTTP status programmatically on the returned
 * `ResponseEntity` rather than via `@ResponseStatus` — the status is only
 * visible in the compiled method body, which [ResponseEntityStatusScanner]
 * reads. Mirrors the real-world `createCampaign` shape (suspend + builder).
 *
 * The class is `open` so [createOpenSuspend] can be too: that is the shape the
 * `all-open` compiler plugin gives every handler in a Spring Boot Kotlin
 * project, and it compiles the body into a separate `$suspendImpl` method.
 * The other handlers stay final and so keep covering the direct-body path.
 */
@RestController
@RequestMapping("/campaigns")
open class StatusController {

    @PostMapping("/status-enum")
    fun createViaStatusEnum(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity.status(HttpStatus.CREATED).body(Campaign("x"))

    @PostMapping("/factory")
    fun createViaFactory(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity.created(URI.create("/campaigns/x")).body(Campaign("x"))

    @PostMapping("/status-int")
    fun createViaStatusInt(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity.status(201).body(Campaign("x"))

    @PostMapping("/ctor")
    fun createViaConstructor(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity(Campaign("x"), HttpStatus.CREATED)

    @PostMapping("/suspend")
    suspend fun createSuspend(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity.status(HttpStatus.CREATED).body(Campaign("x"))

    @PostMapping("/open-suspend")
    open suspend fun createOpenSuspend(@RequestBody body: NewCampaign): ResponseEntity<Campaign> =
        ResponseEntity.status(HttpStatus.CREATED).body(Campaign("x"))

    @GetMapping("/ok")
    fun okStays200(): ResponseEntity<Campaign> = ResponseEntity.ok(Campaign("x"))

    @PostMapping("/no-content")
    fun deleteViaNoContent(): ResponseEntity<Void> = ResponseEntity.noContent().build()
}
