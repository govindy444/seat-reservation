package com.example.seats.reservation;

/** {@code replayed} = this idempotency key already produced {@code reservation}; nothing new was booked. */
public record ReserveOutcome(Reservation reservation, boolean replayed) {
}
