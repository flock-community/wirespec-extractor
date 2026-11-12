package community.flock.wirespec.extractor.fixtures.jaxrs.built;

import io.smallrye.mutiny.Uni;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.RestResponse;

import java.net.URI;
import java.util.List;

/**
 * Resource methods that set their status and entity by building a {@code Response} (or a
 * Quarkus {@code RestResponse}) in the body, one shape per method.
 */
@Path("/built")
public class BuiltResponsesResource {

    private final FruitRepository repository = new FruitRepository();

    // --- Status from a factory, an int, or a status constant ----------------------------

    @POST
    @Path("/created")
    public Response created() {
        return Response.created(URI.create("/built/1")).build();
    }

    @POST
    @Path("/accepted")
    public Response accepted() {
        return Response.accepted().build();
    }

    @GET
    @Path("/not-modified")
    public Response notModified() {
        return Response.notModified().build();
    }

    @GET
    @Path("/server-error")
    public Response serverError() {
        return Response.serverError().build();
    }

    @GET
    @Path("/teapot")
    public Response teapot() {
        return Response.status(418).build();
    }

    @GET
    @Path("/conflict")
    public Response conflict() {
        return Response.status(Response.Status.CONFLICT.getStatusCode()).build();
    }

    @GET
    @Path("/gone")
    public Response gone() {
        return Response.status(Response.Status.fromStatusCode(410)).build();
    }

    // --- Several statuses and typed entities --------------------------------------------

    @GET
    @Path("/{name}")
    public Response byName(@PathParam("name") String name) {
        Fruit fruit = repository.find(name);
        if (fruit == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(new Problem("no such fruit")).build();
        }
        return Response.ok(fruit).build();
    }

    /** The entity keeps the generic return type of the call that produced it. */
    @GET
    public Response list() {
        List<Fruit> fruits = repository.all();
        return Response.ok(fruits).header("X-Count", fruits.size()).build();
    }

    /** A builder kept in a local, set up differently per branch and finished once. */
    @POST
    public Response create(Fruit fruit) {
        Response.ResponseBuilder builder;
        if (repository.find(fruit.name()) != null) {
            builder = Response.status(Response.Status.CONFLICT);
        } else {
            builder = Response.created(URI.create("/built/" + fruit.name())).entity(fruit);
        }
        return builder.type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        return repository.remove(name) ? Response.noContent().build() : Response.status(404).build();
    }

    @GET
    @Path("/generic")
    public Response generic() {
        return Response.ok(new GenericEntity<List<Fruit>>(repository.all()) {}).build();
    }

    /** One status, entities of different types. */
    @GET
    @Path("/mixed")
    public Response mixed(@QueryParam("verbose") boolean verbose) {
        if (verbose) {
            return Response.ok(repository.all()).build();
        }
        return Response.ok("short").build();
    }

    // --- Not readable from the body: the signature's fallback, with a warning -----------

    @GET
    @Path("/dynamic")
    public Response dynamic(@QueryParam("code") int code) {
        return Response.status(code).build();
    }

    @GET
    @Path("/delegated")
    public Response delegated() {
        return notFound();
    }

    @GET
    @Path("/async")
    public Uni<Response> async() {
        return Uni.createFrom().item(Response.ok("later").build());
    }

    private static Response notFound() {
        return Response.status(404).build();
    }

    // --- Explicit annotations win ---------------------------------------------------------

    /** The declared responses replace the inferred ones; the inferred body backs the declared 200. */
    @GET
    @Path("/declared/{name}")
    @ApiResponses({
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = Problem.class))),
    })
    public Response declared(@PathParam("name") String name) {
        Fruit fruit = repository.find(name);
        return fruit == null ? Response.status(410).build() : Response.ok(fruit).build();
    }

    /** Declared, so the unreadable body is no reason to warn. */
    @GET
    @Path("/declared-delegated")
    @ApiResponse(responseCode = "404", content = @Content(schema = @Schema(implementation = Problem.class)))
    public Response declaredDelegated() {
        return notFound();
    }

    @POST
    @Path("/explicit")
    @ResponseStatus(201)
    public Response explicit() {
        return Response.ok("made").build();
    }

    // --- Quarkus RestResponse -------------------------------------------------------------

    /** A typed RestResponse can only carry its type argument. */
    @GET
    @Path("/rest/{name}")
    public RestResponse<Fruit> rest(@PathParam("name") String name) {
        Fruit fruit = repository.find(name);
        return fruit == null ? RestResponse.notFound() : RestResponse.ok(fruit);
    }

    @PUT
    @Path("/rest/{name}")
    public RestResponse<Fruit> restBuilder(@PathParam("name") String name, Fruit fruit) {
        if (!name.equals(fruit.name())) {
            return RestResponse.ResponseBuilder.<Fruit>create(RestResponse.Status.BAD_REQUEST).build();
        }
        return RestResponse.ResponseBuilder.ok(fruit).status(RestResponse.StatusCode.ACCEPTED).build();
    }

    @SuppressWarnings("rawtypes")
    @POST
    @Path("/rest/raw")
    public RestResponse raw() {
        return RestResponse.status(RestResponse.Status.CREATED, "made");
    }
}
