package com.acme.api;

import jakarta.ws.rs.Path;

@Path("/baskets")
public class BasketResource implements BasketContract {

    @Override
    public Fruit basket(String id) {
        return new Fruit(id, null);
    }
}
