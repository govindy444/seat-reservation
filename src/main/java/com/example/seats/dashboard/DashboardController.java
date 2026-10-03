package com.example.seats.dashboard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats.observability.ReservationMetrics;
import com.example.seats.observability.ReservationMetrics.ShowSeats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Compact JSON for the live dashboard page. Reads the same Micrometer registry that /actuator/prometheus
 * exports, so the two can never disagree; it just avoids shipping ~200KB of histogram buckets every second.
 */
@RestController
public class DashboardController {

    private final MeterRegistry registry;
    private final ReservationMetrics metrics;
    private final String commit;

    public DashboardController(MeterRegistry registry, ReservationMetrics metrics,
                               @Value("${info.app.commit:local}") String commit) {
        this.registry = registry;
        this.metrics = metrics;
        this.commit = commit;
    }

    public record Stats(Instant time, String commit, double confirmed, double cancelled, Map<String, Double> declined,
                        Map<String, Double> httpByStatusClass, double httpTotal, Pool pool, List<ShowSeats> shows) {
    }

    public record Pool(double active, double idle, double pending, double max) {
    }

    @GetMapping("/dashboard/stats")
    Stats stats() {
        Map<String, Double> declined = new TreeMap<>();
        for (Counter c : registry.find("reservations.declined").counters()) {
            declined.put(c.getId().getTag("reason"), c.count());
        }

        // API traffic only: the dashboard's own polling and actuator probes are excluded
        Map<String, Double> byClass = new LinkedHashMap<>(Map.of("2xx", 0.0, "4xx", 0.0, "5xx", 0.0));
        double total = 0;
        for (Timer t : registry.find("http.server.requests").timers()) {
            String uri = String.valueOf(t.getId().getTag("uri"));
            if (uri.startsWith("/actuator") || uri.startsWith("/dashboard")) {
                continue;
            }
            String status = String.valueOf(t.getId().getTag("status"));
            String cls = status.isEmpty() ? "other" : status.charAt(0) + "xx";
            byClass.merge(cls, (double) t.count(), Double::sum);
            total += t.count();
        }

        return new Stats(Instant.now(), commit,
                counter("reservations.confirmed"), counter("reservations.cancelled"), declined, byClass, total,
                new Pool(gauge("hikaricp.connections.active"), gauge("hikaricp.connections.idle"),
                        gauge("hikaricp.connections.pending"), gauge("hikaricp.connections.max")),
                metrics.snapshot());
    }

    private double counter(String name) {
        Counter c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private double gauge(String name) {
        Gauge g = registry.find(name).gauge();
        return g == null ? 0 : g.value();
    }
}
