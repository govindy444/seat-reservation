package com.example.seats.observability;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Counters are incremented only after the transaction commits, so they count facts, not attempts.
 * Seat gauges are read from the seats table (the source of truth) for the most recent shows every few
 * seconds; keeping a per-show counter row inside the reserve transaction would serialize every reserve
 * on that row.
 *
 * Reconciliation in PromQL:  sum by (show_id) (show_seats) == show_capacity
 */
@Component
public class ReservationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReservationMetrics.class);
    static final int TRACKED_SHOWS = 10;
    /** Pre-registered so every reason is exported as 0 from startup; absent series make rate()/alerts misbehave. */
    static final List<String> DECLINE_REASONS = List.of("seat-taken", "per-user-limit", "idempotent-replay",
            "idempotency-key-mismatch", "unknown-seat", "not-found", "bad-request");

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Counter confirmed;
    private final Counter seatsConfirmed;
    private final Counter cancelled;
    private final Counter seatsReleased;
    private final MultiGauge showSeats;
    private final MultiGauge showCapacity;

    public ReservationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.confirmed = Counter.builder("reservations.confirmed").description("Reservations created").register(registry);
        this.seatsConfirmed = Counter.builder("seats.confirmed").description("Seats sold by new reservations").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled").register(registry);
        this.seatsReleased = Counter.builder("seats.released").description("Seats returned to available by cancels").register(registry);
        this.showSeats = MultiGauge.builder("show.seats").description("Seats per show by status").register(registry);
        DECLINE_REASONS.forEach(this::declinedCounter);
        this.showCapacity = MultiGauge.builder("show.capacity").description("Total seats per show").register(registry);
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    /** reason: seat-taken, per-user-limit, idempotent-replay, idempotency-key-mismatch, unknown-seat, not-found, bad-request */
    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined").description("Reservation requests declined, by reason")
                .tag("reason", reason).register(registry);
    }

    public void cancelled(int seats) {
        cancelled.increment();
        seatsReleased.increment(seats);
    }

    @Scheduled(fixedDelayString = "${app.metrics.seat-gauge-interval:5s}", initialDelay = 0)
    public void refreshSeatGauges() {
        try {
            record Row(UUID showId, String status, long n, long total) {
            }
            List<Row> rows = jdbc.query("""
                    WITH recent AS (SELECT id, total_seats FROM shows ORDER BY created_at DESC LIMIT ?)
                    SELECT r.id AS show_id, st.status, count(s.label) AS n, r.total_seats
                    FROM recent r
                    CROSS JOIN (VALUES ('available'), ('held'), ('confirmed')) AS st(status)
                    LEFT JOIN seats s ON s.show_id = r.id AND s.status = st.status
                    GROUP BY r.id, st.status, r.total_seats
                    """, (rs, i) -> new Row(rs.getObject("show_id", UUID.class), rs.getString("status"),
                    rs.getLong("n"), rs.getLong("total_seats")), TRACKED_SHOWS);

            showSeats.register(rows.stream().map(r -> MultiGauge.Row.of(
                    Tags.of("show_id", r.showId().toString(), "status", r.status()), r.n())).toList(), true);
            showCapacity.register(rows.stream().map(Row::showId).distinct().map(id -> MultiGauge.Row.of(
                    Tags.of("show_id", id.toString()),
                    rows.stream().filter(r -> r.showId().equals(id)).findFirst().orElseThrow().total())).toList(), true);
        } catch (Exception e) {
            // DB down: keep last values; readiness reports the outage
            log.warn("seat gauge refresh failed: {}", e.toString());
        }
    }
}
