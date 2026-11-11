package community.flock.wirespec.extractor.fixtures.quarkus

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam

/** A contract carrying only method-level annotations; the root `@Path` is on the implementation. */
interface BasketContract {
    @GET
    @Path("/{id}")
    fun basket(@PathParam("id") id: String, @QueryParam("expand") expand: Boolean?): FruitDto
}

@Path("/baskets")
class BasketResource : BasketContract {
    override fun basket(id: String, expand: Boolean?): FruitDto = FruitDto(id, null)
}

/** A generic contract: the implementation supplies the concrete entity type. */
interface CrudContract<T> {
    @POST
    fun create(item: T): T
}

@Path("/crates")
class CrateResource : CrudContract<FruitDto> {
    override fun create(item: FruitDto): FruitDto = item
}

/** Both the interface and its implementation are root resources. */
@Path("/orchards")
interface OrchardApi {
    @GET
    fun orchards(): List<FruitDto>
}

@Path("/orchards")
class OrchardResource : OrchardApi {
    override fun orchards(): List<FruitDto> = emptyList()
}
