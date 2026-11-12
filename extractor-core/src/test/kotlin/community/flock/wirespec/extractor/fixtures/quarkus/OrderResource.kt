package community.flock.wirespec.extractor.fixtures.quarkus

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestPath

/**
 * A Kotlin resource building untyped `Response`s the Kotlin way: `?.let … ?:`, `when` and
 * `suspend` functions. The class is `open`, as the `all-open` plugin makes `@Path` classes
 * in a Quarkus Kotlin project, so [openSuspended] compiles its body into a `$suspendImpl`.
 */
@Path("/orders")
open class OrderResource {

    private val orders = mutableMapOf<String, FruitDto>()

    @GET
    @Path("/{id}")
    fun byId(@RestPath id: String): Response =
        orders[id]?.let { Response.ok(it).build() } ?: Response.status(Response.Status.NOT_FOUND).build()

    @DELETE
    @Path("/{id}")
    fun delete(@RestPath id: String): Response = when (orders.remove(id)) {
        null -> Response.status(404).build()
        else -> Response.noContent().build()
    }

    @GET
    @Path("/{id}/suspended")
    suspend fun suspended(@RestPath id: String): Response {
        val order = lookup(id)
        return if (order == null) Response.status(Response.Status.NOT_FOUND).build() else Response.ok(order).build()
    }

    @GET
    @Path("/{id}/open")
    open suspend fun openSuspended(@RestPath id: String): Response {
        val order = lookup(id)
        return if (order == null) Response.status(Response.Status.NOT_FOUND).build() else Response.ok(order).build()
    }

    private suspend fun lookup(id: String): FruitDto? = orders[id]
}
