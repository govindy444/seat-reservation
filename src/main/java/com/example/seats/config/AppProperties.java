package com.example.seats.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, String adminKey) {

    public record Jwt(String secret, Duration ttl) {
    }
}
