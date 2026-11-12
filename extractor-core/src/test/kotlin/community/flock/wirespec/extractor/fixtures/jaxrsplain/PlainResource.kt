package community.flock.wirespec.extractor.fixtures.jaxrsplain

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path

/** A JAX-RS resource with nothing but JAX-RS annotations, for scanning it with and without the API. */
@Path("/plain")
class PlainResource {
    @GET
    fun plain(): String = "plain"
}
