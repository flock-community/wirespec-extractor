package com.acme.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/yolo")
public class YoloResource {

    @GET
    public String yolo() {
        return "yolo";
    }
}
