package com.acme.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** An untyped Response whose status and entity are set in the method body. */
@Path("/ready")
@ApplicationScoped
public class ReadinessEndpoint {

    @Inject
    HealthCheckService healthCheckService;

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
