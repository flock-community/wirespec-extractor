package com.acme.api;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class HealthCheckService {

    public boolean isReady() {
        return true;
    }
}
