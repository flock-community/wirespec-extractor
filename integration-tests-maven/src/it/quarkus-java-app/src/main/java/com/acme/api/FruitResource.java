package com.acme.api;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

/** A resource written the Quarkus REST way rather than with plain JAX-RS annotations. */
@Path("/fruits")
public class FruitResource {

    @GET
    public List<Fruit> list(@RestQuery String filter, @RestHeader String xTraceId) {
        return List.of();
    }

    @GET
    @Path("/{name}")
    public Uni<Fruit> byName(@RestPath String name) {
        return Uni.createFrom().nullItem();
    }

    /** No annotation at all: Quarkus binds `name` to the path variable of the same name. */
    @GET
    @Path("/{name}/description")
    public String description(String name) {
        return name;
    }

    @GET
    @Path("/stream")
    public Multi<Fruit> stream() {
        return Multi.createFrom().empty();
    }

    @POST
    @ResponseStatus(201)
    public RestResponse<Fruit> create(Fruit fruit) {
        return RestResponse.ok(fruit);
    }

    @PUT
    @Path("/{name}")
    public Uni<RestResponse<Fruit>> update(@PathParam("name") String name, Fruit fruit) {
        return Uni.createFrom().item(RestResponse.ok(fruit));
    }

    @DELETE
    @Path("/{name}")
    public Response delete(@RestPath String name) {
        return Response.noContent().build();
    }

    /** Built by a helper, so not readable from this body: MicroProfile OpenAPI declares it. */
    @GET
    @Path("/{name}/stock")
    @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Integer.class)))
    @APIResponse(responseCode = "404")
    public Response stock(@RestPath String name) {
        return stockOf(name);
    }

    private static Response stockOf(String name) {
        return name.isEmpty() ? Response.status(Response.Status.NOT_FOUND).build() : Response.ok(42).build();
    }
}
