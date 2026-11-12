package com.acme.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/** Returns an API type: without the API, reflecting on its methods throws NoClassDefFoundError. */
@Path("/ready")
public class ReadinessResource {

    private volatile boolean ready = true;

    @GET
    public Response ready() {
        if (ready) {
            return Response.ok().entity("Ready").build();
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("Not Ready").build();
    }
}
