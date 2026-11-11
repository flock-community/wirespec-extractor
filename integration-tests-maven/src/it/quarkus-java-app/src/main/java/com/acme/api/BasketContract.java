package com.acme.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** Method-level annotations only; the root `@Path` lives on the implementing resource. */
public interface BasketContract {

    @GET
    @Path("/{id}")
    Fruit basket(@PathParam("id") String id);
}
