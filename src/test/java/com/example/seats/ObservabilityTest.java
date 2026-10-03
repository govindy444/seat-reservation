package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.seats.observability.ReservationMetrics;
import com.example.seats.support.ApiClient;

import io.micrometer.core.instrument.MeterRegistry;

// tests switch metrics export off by default; we need the real /actuator/prometheus output
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityTest {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    @Autowired
    ReservationMetrics metrics;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void countersTrackOutcomesByReason() {
        String show = api.createShow("""
                {"name":"m","seats":["M1","M2","M3"],"price_paise":100,"per_user_limit":1}""");
        String a = api.token("m-a-" + UUID.randomUUID());
        String b = api.token("m-b-" + UUID.randomUUID());
        double confirmed = counter("reservations.confirmed");
        double taken = counter("reservations.declined", "reason", "seat-taken");
        double limit = counter("reservations.declined", "reason", "per-user-limit");
        double replay = counter("reservations.declined", "reason", "idempotent-replay");
        double cancelled = counter("reservations.cancelled");

        String key = UUID.randomUUID().toString();
        String id = api.reserve(show, a, key, "M1").field("reservation_id");
        api.reserve(show, a, key, "M1");                          // replay
        api.reserve(show, b, UUID.randomUUID().toString(), "M1"); // taken
        api.reserve(show, a, UUID.randomUUID().toString(), "M2"); // over limit 1
        api.post("/reservations/" + id + "/cancel", "", a);
        api.post("/reservations/" + id + "/cancel", "", a);       // repeat cancel: not counted twice

        assertThat(counter("reservations.confirmed") - confirmed).isEqualTo(1);
        assertThat(counter("reservations.declined", "reason", "idempotent-replay") - replay).isEqualTo(1);
        assertThat(counter("reservations.declined", "reason", "seat-taken") - taken).isEqualTo(1);
        assertThat(counter("reservations.declined", "reason", "per-user-limit") - limit).isEqualTo(1);
        assertThat(counter("reservations.cancelled") - cancelled).isEqualTo(1);
    }

    @Test
    void seatGaugesReconcileWithApiState() {
        String show = api.createShow("""
                {"name":"g","seats":["G1","G2","G3","G4"],"price_paise":100}""");
        api.reserve(show, api.token("g-" + UUID.randomUUID()), UUID.randomUUID().toString(), "G1", "G2");
        metrics.refreshSeatGauges();

        String body = api.get("/actuator/prometheus", null).body();
        assertThat(body).contains("show_seats{show_id=\"" + show + "\",status=\"available\"} 2.0");
        assertThat(body).contains("show_seats{show_id=\"" + show + "\",status=\"confirmed\"} 2.0");
        assertThat(body).contains("show_seats{show_id=\"" + show + "\",status=\"held\"} 0.0");
        assertThat(body).contains("show_capacity{show_id=\"" + show + "\"} 4.0");
        assertThat(body).contains("reservations_confirmed_total", "reservations_declined_total", "hikaricp_connections_pending");
        assertThat(api.get("/shows/" + show, null).body()).contains("\"available\":2", "\"confirmed\":2");
    }

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        var supplied = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/me"))
                .header("X-Request-Id", "trace-abc-123").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(supplied.statusCode()).isEqualTo(401); // set even when security rejects
        assertThat(supplied.headers().firstValue("X-Request-Id")).hasValue("trace-abc-123");

        var generated = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/me"))
                .header("X-Request-Id", "bad id with spaces\"").build(), HttpResponse.BodyHandlers.discarding());
        assertThat(generated.headers().firstValue("X-Request-Id")).hasValueSatisfying(v ->
                assertThat(v).matches("[0-9a-f-]{36}"));
    }

    @Test
    void readinessIncludesDatabaseProbe() {
        var r = api.get("/actuator/health/readiness", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("\"database\"", "latency_ms");
    }
}
