package com.example.seats.reservation;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    public record LockedSeat(String label, String status) {
    }

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
