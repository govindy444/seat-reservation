package com.example.seats.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TokenRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_.-]{1,64}") String userId,
        String adminKey) {
}
