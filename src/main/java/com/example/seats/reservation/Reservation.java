package com.example.seats.reservation;

import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status) {
}
