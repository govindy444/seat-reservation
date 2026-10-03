package com.example.seats.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.seats.api.ApiException;
import com.example.seats.show.Show;
import com.example.seats.show.ShowRepository;

@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final ShowRepository shows;
    private final TransactionTemplate tx;

    public ReservationService(ReservationRepository reservations, ShowRepository shows, TransactionTemplate tx) {
        this.reservations = reservations;
        this.shows = shows;
        this.tx = tx;
    }

    /**
     * All-or-nothing: either every requested seat is confirmed to this user, or none is and the caller gets 409.
     * The transaction is driven explicitly so database exceptions can be handled after rollback.
     */
    public Reservation reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        Set<String> unique = new LinkedHashSet<>(requestedSeats);
        if (unique.size() != requestedSeats.size()) {
            throw ApiException.badRequest("duplicate seats in request");
        }
        List<String> seats = List.copyOf(unique);
        List<String> sorted = seats.stream().sorted().toList();
        String requestHash = hash(showId + "|" + String.join(",", sorted));

        try {
            return tx.execute(status -> {
                Show show = shows.findShow(showId).orElseThrow(() -> ApiException.notFound("show"));

                List<ReservationRepository.LockedSeat> locked = reservations.lockSeats(showId, sorted);
                if (locked.size() != sorted.size()) {
                    Set<String> found = locked.stream().map(ReservationRepository.LockedSeat::label).collect(Collectors.toSet());
                    List<String> unknown = sorted.stream().filter(s -> !found.contains(s)).toList();
                    throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_seat", "unknown seats: " + unknown);
                }
                List<String> taken = locked.stream().filter(s -> !"available".equals(s.status()))
                        .map(ReservationRepository.LockedSeat::label).toList();
                if (!taken.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "seat_taken", "seats already taken: " + taken);
                }

                long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
                Reservation r = new Reservation(UUID.randomUUID(), showId, userId, seats, amount, "confirmed");
                reservations.insertReservation(r, idempotencyKey, requestHash);

                int claimed = reservations.claimSeats(showId, sorted, r.reservationId(), userId);
                if (claimed != sorted.size()) {
                    // unreachable while we hold the row locks; fail loudly rather than sell a partial set
                    throw new IllegalStateException("claimed " + claimed + " of " + sorted.size() + " locked seats");
                }
                return r;
            });
        } catch (DuplicateKeyException e) {
            // same (user, idempotency_key) already used; replay semantics arrive with the idempotency step
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused", "idempotency key already used");
        }
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
