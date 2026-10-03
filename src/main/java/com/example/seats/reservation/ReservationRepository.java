package com.example.seats.reservation;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    public record LockedSeat(String label, String status) {
    }

    private final JdbcTemplate jdbc;

    public record StoredReservation(Reservation reservation, String requestHash) {
    }

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes all reserve/cancel work for one (show, user) pair for the rest of the transaction.
     * Always taken BEFORE any seat row lock, so lock order is global: user lock, then seats in label order.
     * A hash collision between two pairs only adds waiting, never a wrong answer.
     */
    public void lockUser(UUID showId, String userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, showId + ":" + userId);
    }

    /** Seats this user currently holds or owns in the show; read after lockUser, so no concurrent insert can hide. */
    public int countUserSeats(UUID showId, String userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND user_id = ? AND status <> 'available'",
                Integer.class, showId, userId);
        return n == null ? 0 : n;
    }

    public record Precheck(boolean showExists, int existing, int available, boolean keySeen) {
    }

    /**
     * One lock-free round trip on committed data: how many of the requested seats exist, how many are still
     * available, and whether this (user, key) was already used. Used only to DECLINE early; never to confirm.
     */
    public Precheck precheck(UUID showId, List<String> labels, String userId, String idempotencyKey) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM shows WHERE id = ?) AS show_exists,
                           count(*) AS existing,
                           count(*) FILTER (WHERE status = 'available') AS available,
                           EXISTS (SELECT 1 FROM reservations WHERE user_id = ? AND idempotency_key = ?) AS key_seen
                    FROM seats WHERE show_id = ? AND label = ANY(?)
                    """);
            ps.setObject(1, showId);
            ps.setString(2, userId);
            ps.setString(3, idempotencyKey);
            ps.setObject(4, showId);
            ps.setArray(5, textArray(con, labels));
            return ps;
        }, rs -> {
            rs.next();
            return new Precheck(rs.getBoolean("show_exists"), rs.getInt("existing"), rs.getInt("available"),
                    rs.getBoolean("key_seen"));
        });
    }

    public Optional<StoredReservation> findByIdempotencyKey(String userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, show_id, user_id, seats, amount_paise, status, request_hash
                FROM reservations WHERE user_id = ? AND idempotency_key = ?
                """, (rs, i) -> new StoredReservation(mapReservation(rs), rs.getString("request_hash")),
                userId, idempotencyKey).stream().findFirst();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query("""
                SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?
                """, (rs, i) -> mapReservation(rs), id).stream().findFirst();
    }

    /** Re-reads the reservation under a row lock so a concurrent cancel of the same reservation sees our result. */
    public Reservation lockReservation(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ? FOR UPDATE
                """, (rs, i) -> mapReservation(rs), id);
    }

    /**
     * Releases only seats still owned by THIS reservation. If a seat was somehow re-sold, its reservation_id
     * points elsewhere and this statement cannot touch it — a release can never resurrect someone else's seat.
     */
    public int releaseSeats(UUID showId, UUID reservationId) {
        return jdbc.update("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL, updated_at = now()
                WHERE show_id = ? AND reservation_id = ?
                """, showId, reservationId);
    }

    public void markCancelled(UUID reservationId) {
        jdbc.update("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ? AND status = 'confirmed'",
                reservationId);
    }

    private static Reservation mapReservation(ResultSet rs) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("show_id", UUID.class),
                rs.getString("user_id"),
                Arrays.asList((String[]) rs.getArray("seats").getArray()),
                rs.getLong("amount_paise"),
                rs.getString("status"));
    }

    /**
     * Row-locks the requested seats in label order. LockRows sits above the Sort in the plan, so locks are
     * acquired in that deterministic order: two multi-seat requests can wait on each other but never deadlock.
     */
    public List<LockedSeat> lockSeats(UUID showId, List<String> sortedLabels) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT label, status FROM seats
                    WHERE show_id = ? AND label = ANY(?)
                    ORDER BY label
                    FOR UPDATE
                    """);
            ps.setObject(1, showId);
            ps.setArray(2, textArray(con, sortedLabels));
            return ps;
        }, (rs, i) -> new LockedSeat(rs.getString("label"), rs.getString("status")));
    }

    public void insertReservation(Reservation r, String idempotencyKey, String requestHash) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, r.reservationId());
            ps.setObject(2, r.showId());
            ps.setString(3, r.userId());
            ps.setString(4, idempotencyKey);
            ps.setString(5, requestHash);
            ps.setArray(6, textArray(con, r.seats()));
            ps.setLong(7, r.amountPaise());
            ps.setString(8, r.status());
            return ps;
        });
    }

    /** Guarded on status = 'available': even without the row locks this could never take a seat someone else owns. */
    public int claimSeats(UUID showId, List<String> labels, UUID reservationId, String userId) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?, updated_at = now()
                    WHERE show_id = ? AND label = ANY(?) AND status = 'available'
                    """);
            ps.setObject(1, reservationId);
            ps.setString(2, userId);
            ps.setObject(3, showId);
            ps.setArray(4, textArray(con, labels));
            return ps;
        });
    }

    private static Array textArray(Connection con, List<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray());
    }
}
