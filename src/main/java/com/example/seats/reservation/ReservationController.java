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

    /**
     * 201 = new reservation. 200 = idempotent replay of an earlier one (nothing new booked), so retries never
     * show up as extra 201s. Idempotency key may come from the Idempotency-Key header or the body.
     */
    @PostMapping("/shows/{showId}/reserve")
    ResponseEntity<Reservation> reserve(@PathVariable UUID showId,
                                        @Valid @RequestBody ReserveRequest req,
                                        @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
                                        @AuthenticationPrincipal Jwt jwt) {
        String key = resolveKey(headerKey, req.idempotencyKey());
        ReserveOutcome outcome = service.reserve(showId, jwt.getSubject(), req.seats(), key);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(outcome.reservation());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    Reservation cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(reservationId, jwt.getSubject());
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
