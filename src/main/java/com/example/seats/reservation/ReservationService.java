package com.example.seats.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.seats.api.ApiException;
import com.example.seats.observability.ReservationMetrics;
import com.example.seats.reservation.ReservationRepository.LockedSeat;
import com.example.seats.reservation.ReservationRepository.StoredReservation;
import com.example.seats.show.Show;
import com.example.seats.show.ShowRepository;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservations;
    private final ShowRepository shows;
    private final TransactionTemplate tx;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository reservations, ShowRepository shows, TransactionTemplate tx,
                              ReservationMetrics metrics) {
        this.reservations = reservations;
        this.shows = shows;
        this.tx = tx;
        this.metrics = metrics;
    }

    /**
     * One transaction, locks always in the same order: (show,user) advisory lock, then seat rows by label.
     * <ol>
     *   <li>idempotency: a stored reservation for (user, key) is replayed if the request matches, else 409</li>
     *   <li>per-user limit: counted under the user lock, so parallel requests from one user cannot overshoot</li>
     *   <li>seats: all-or-nothing; any taken seat rolls back the whole request with 409</li>
     * </ol>
     * Declines are not stored, so retrying a declined request re-evaluates it.
     */
    public ReserveOutcome reserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
        try {
            ReserveOutcome outcome = doReserve(showId, userId, requestedSeats, idempotencyKey);
            Reservation r = outcome.reservation();
            if (outcome.replayed()) {
                metrics.declined("idempotent-replay");
            } else {
                metrics.confirmed(r.seats().size());
            }
            log.atInfo().setMessage("reserve")
                    .addKeyValue("outcome", outcome.replayed() ? "replayed" : "confirmed")
                    .addKeyValue("show_id", showId).addKeyValue("user_id", userId)
                    .addKeyValue("seats", r.seats()).addKeyValue("reservation_id", r.reservationId())
                    .addKeyValue("amount_paise", r.amountPaise()).log();
            return outcome;
        } catch (ApiException e) {
            String reason = e.code().replace('_', '-');
            metrics.declined(reason);
            log.atInfo().setMessage("reserve")
                    .addKeyValue("outcome", "declined").addKeyValue("reason", reason)
                    .addKeyValue("show_id", showId).addKeyValue("user_id", userId)
                    .addKeyValue("seats", requestedSeats).log();
            throw e;
        }
    }

    private ReserveOutcome doReserve(UUID showId, String userId, List<String> requestedSeats, String idempotencyKey) {
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
                reservations.lockUser(showId, userId);

                Optional<StoredReservation> existing = reservations.findByIdempotencyKey(userId, idempotencyKey);
                if (existing.isPresent()) {
                    return replay(existing.get(), requestHash);
                }

                int owned = reservations.countUserSeats(showId, userId);
                if (owned + seats.size() > show.perUserLimit()) {
                    throw new ApiException(HttpStatus.CONFLICT, "per_user_limit",
                            "per-user limit is " + show.perUserLimit() + " seats; you hold " + owned);
                }

                List<LockedSeat> locked = reservations.lockSeats(showId, sorted);
                if (locked.size() != sorted.size()) {
                    Set<String> found = locked.stream().map(LockedSeat::label).collect(Collectors.toSet());
                    List<String> unknown = sorted.stream().filter(s -> !found.contains(s)).toList();
                    throw new ApiException(HttpStatus.BAD_REQUEST, "unknown_seat", "unknown seats: " + unknown);
                }
                List<String> taken = locked.stream().filter(s -> !"available".equals(s.status()))
                        .map(LockedSeat::label).toList();
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
                return new ReserveOutcome(r, false);
            });
        } catch (DuplicateKeyException e) {
            // Same key raced in on a different show (different user lock); the winner has committed by now.
            StoredReservation stored = tx.execute(s -> reservations.findByIdempotencyKey(userId, idempotencyKey))
                    .orElseThrow(() -> e);
            return replay(stored, requestHash);
        }
    }

    /**
     * Owner-only, idempotent cancel. Same lock order as reserve: (show,user) lock, reservation row, seats by label.
     * Someone else's reservation is reported as 404 — indistinguishable from a missing one, so ids can't be probed.
     */
    public Reservation cancel(UUID reservationId, String userId) {
        record Result(Reservation reservation, boolean changed) {
        }
        Result result = tx.execute(status -> {
            Reservation found = reservations.findById(reservationId)
                    .filter(r -> r.userId().equals(userId))
                    .orElseThrow(() -> ApiException.notFound("reservation"));

            reservations.lockUser(found.showId(), userId);
            Reservation current = reservations.lockReservation(reservationId);
            if (!"confirmed".equals(current.status())) {
                return new Result(current, false); // already cancelled: no-op, same answer
            }

            reservations.lockSeats(current.showId(), current.seats().stream().sorted().toList());
            int released = reservations.releaseSeats(current.showId(), reservationId);
            if (released != current.seats().size()) {
                // the seat rows disagree with the reservation; refuse rather than guess
                throw new IllegalStateException("reservation " + reservationId + " owns " + released
                        + " of " + current.seats().size() + " seats");
            }
            reservations.markCancelled(reservationId);
            return new Result(new Reservation(current.reservationId(), current.showId(), current.userId(),
                    current.seats(), current.amountPaise(), "cancelled"), true);
        });
        Reservation r = result.reservation();
        if (result.changed()) {
            metrics.cancelled(r.seats().size());
        }
        log.atInfo().setMessage("cancel")
                .addKeyValue("outcome", result.changed() ? "cancelled" : "already-cancelled")
                .addKeyValue("reservation_id", reservationId).addKeyValue("show_id", r.showId())
                .addKeyValue("user_id", userId).addKeyValue("seats", r.seats()).log();
        return r;
    }

    private static ReserveOutcome replay(StoredReservation stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_mismatch",
                    "idempotency key was already used for a different request");
        }
        return new ReserveOutcome(stored.reservation(), true);
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
