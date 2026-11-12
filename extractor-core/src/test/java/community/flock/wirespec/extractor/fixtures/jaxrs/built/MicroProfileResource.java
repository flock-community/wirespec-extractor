package community.flock.wirespec.extractor.fixtures.jaxrs.built;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

/** Responses declared with MicroProfile OpenAPI, as Quarkus projects do with smallrye-openapi. */
@Path("/micro-profile")
public class MicroProfileResource {

    /** Repeated standalone annotations. */
    @GET
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Fruit.class)))
    @APIResponse(responseCode = "503")
    public Response list() {
        return Response.ok().build();
    }

    /** The container; the declared 200 has no content, so the body built for 200 backs it. */
    @GET
    @Path("/{name}")
    @APIResponses({
            @APIResponse(responseCode = "200"),
            @APIResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = Problem.class))),
    })
    public Response byName(@PathParam("name") String name) {
        return Response.ok(new Fruit(name)).build();
    }
}
