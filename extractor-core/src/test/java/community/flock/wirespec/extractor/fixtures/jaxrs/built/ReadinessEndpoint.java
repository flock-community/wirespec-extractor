package community.flock.wirespec.extractor.fixtures.jaxrs.built;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The resource from issue #57: an untyped {@code Response} whose status and entity are set in the body. */
@Path("/ready")
public class ReadinessEndpoint {

    HealthCheckService healthCheckService = new HealthCheckService();

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response ready() {
        if (healthCheckService.isReady()) {
            return Response.ok().entity("Ready\n").build();
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("Not Ready\n")
                .build();
    }
}
