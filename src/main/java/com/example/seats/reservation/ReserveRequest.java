package com.example.seats.reservation;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Deliberately has no user field: the caller's identity comes only from the token. */
public record ReserveRequest(
        @NotEmpty @Size(max = 10) List<@NotNull String> seats,
        @Size(max = 128) String idempotencyKey) {
}
