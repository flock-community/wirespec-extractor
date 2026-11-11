package community.flock.wirespec.extractor.fixtures.quarkus

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.jboss.resteasy.reactive.ResponseStatus
import org.jboss.resteasy.reactive.RestHeader
import org.jboss.resteasy.reactive.RestPath
import org.jboss.resteasy.reactive.RestQuery
import org.jboss.resteasy.reactive.RestResponse
import java.util.concurrent.CompletionStage

data class FruitDto(val name: String, val description: String?)

/**
 * A resource written the Quarkus REST way: the `@Rest*` shorthand annotations, bare
 * parameters, Mutiny return types and `RestResponse` rather than plain JAX-RS.
 */
@Path("/fruits")
class FruitResource {

    @GET
    fun list(@RestQuery filter: String?, @RestHeader xTraceId: String?): List<FruitDto> = emptyList()

    @GET
    @Path("/{name}")
    fun byName(@RestPath name: String): Uni<FruitDto> = Uni.createFrom().nullItem()

    /** A bare parameter named after a path variable is that path parameter. */
    @GET
    @Path("/{name}/versions/{version}")
    fun version(name: String, version: Int, uriInfo: UriInfo): FruitDto = FruitDto(name, null)

    @GET
    @Path("/stream")
    fun stream(): Multi<FruitDto> = Multi.createFrom().empty()

    @POST
    @ResponseStatus(201)
    fun create(fruit: FruitDto): RestResponse<FruitDto> = RestResponse.ok(fruit)

    /** `name` is bound explicitly, so the bare `fruit` stays the request entity. */
    @PUT
    @Path("/{name}")
    fun update(@PathParam("name") name: String, fruit: FruitDto): Uni<RestResponse<FruitDto>> =
        Uni.createFrom().item(RestResponse.ok(fruit))

    @DELETE
    @Path("/{name}")
    fun delete(@RestPath name: String): Response = Response.noContent().build()

    @GET
    @Path("/{name}/async")
    fun async(@RestPath name: String): CompletionStage<FruitDto> = throw UnsupportedOperationException()

    @GET
    @Path("/{name}/suspended")
    suspend fun suspended(@RestPath name: String): FruitDto = FruitDto(name, null)
}
