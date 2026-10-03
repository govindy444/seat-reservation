package com.example.seats.reservation;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.api.ApiException;

@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /** Idempotency key may come from the Idempotency-Key header or the body; if both are sent they must match. */
    @PostMapping("/shows/{showId}/reserve")
    ResponseEntity<Reservation> reserve(@PathVariable UUID showId,
                                        @Valid @RequestBody ReserveRequest req,
                                        @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
                                        @AuthenticationPrincipal Jwt jwt) {
        String key = resolveKey(headerKey, req.idempotencyKey());
        Reservation r = service.reserve(showId, jwt.getSubject(), req.seats(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    private static String resolveKey(String header, String body) {
        if (header != null && body != null && !header.equals(body)) {
            throw ApiException.badRequest("Idempotency-Key header and idempotency_key body field differ");
        }
        String key = header != null ? header : body;
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency key required (1-128 chars)");
        }
        return key;
    }
}
