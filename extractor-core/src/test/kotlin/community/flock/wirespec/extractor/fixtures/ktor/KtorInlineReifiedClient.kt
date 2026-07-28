package community.flock.wirespec.extractor.fixtures.ktor

import community.flock.wirespec.extractor.fixtures.dto.UserDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.url

/**
 * Fixture: a Ktor client whose requests are issued from `inline` + `reified`
 * helpers. Because the helper is inlined, a lambda in its body is compiled to a
 * standalone anonymous class — and one that reads the request builder (here via
 * `url`) captures the `HttpRequestBuilder`, so it references exactly the types
 * the client scanner keys on while having no source-level name. Two such
 * helpers leave two nameless classes behind, which used to be reported as
 * colliding controllers and failed the build.
 */
class KtorInlineReifiedClient(private val client: HttpClient) {

    suspend fun listInlineUsers(): List<UserDto> = getAll()

    suspend fun getInlineUser(): UserDto = getSingle()

    private suspend inline fun <reified T> getAll(): T = client.get {
        url("/inline/users")
        trace { "calling: $url" }
    }.body<T>()

    private suspend inline fun <reified T> getSingle(): T = client.get {
        url("/inline/users/single")
        trace { "calling: $url" }
    }.body<T>()

    companion object {
        /** Non-inline on purpose: keeps the lambda above a real anonymous class. */
        fun trace(message: () -> String) {
            message()
        }
    }
}
