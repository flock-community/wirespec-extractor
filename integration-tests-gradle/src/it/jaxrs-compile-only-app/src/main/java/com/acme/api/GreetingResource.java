package com.acme.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** Only JDK types in its signature: without the API, its annotations would silently vanish. */
@Path("/hello")
public class GreetingResource {

    @GET
    public String hello() {
        return "hello";
    }
}
