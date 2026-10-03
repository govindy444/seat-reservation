package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.seats.support.ApiClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CancelTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;
    String show;
    String alice;
    String bob;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        show = api.createShow("""
                {"name":"c","seats":["A1","A2","A3"],"price_paise":500,"per_user_limit":2}""");
        alice = api.token("alice-" + UUID.randomUUID());
        bob = api.token("bob-" + UUID.randomUUID());
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    ApiClient.Resp cancel(String reservationId, String token) {
        return api.post("/reservations/" + reservationId + "/cancel", "", token);
    }

    @Test
    void ownerCancelReleasesSeatsForRebooking() {
        String id = api.reserve(show, alice, key(), "A1", "A2").field("reservation_id");
        var c = cancel(id, alice);
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.field("status")).isEqualTo("cancelled");
        assertThat(api.get("/shows/" + show, null).body()).contains("\"available\":3", "\"confirmed\":0", "\"reconciled\":true");
        assertThat(api.reserve(show, bob, key(), "A1").status()).isEqualTo(201);
    }

    @Test
    void cancelIsIdempotent() {
        String id = api.reserve(show, alice, key(), "A1").field("reservation_id");
        assertThat(cancel(id, alice).status()).isEqualTo(200);
        var again = cancel(id, alice);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.field("status")).isEqualTo("cancelled");
    }

    @Test
    void onlyOwnerCanCancel() {
        String id = api.reserve(show, alice, key(), "A1").field("reservation_id");
        assertThat(cancel(id, bob).status()).isEqualTo(404);
        assertThat(cancel(id, null).status()).isEqualTo(401);
        assertThat(cancel(UUID.randomUUID().toString(), alice).status()).isEqualTo(404);
        assertThat(cancel("not-a-uuid", alice).status()).isEqualTo(400);
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":1");
    }

    @Test
    void lateCancelNeverResurrectsSeatSoldToSomeoneElse() {
        String id = api.reserve(show, alice, key(), "A1").field("reservation_id");
        assertThat(cancel(id, alice).status()).isEqualTo(200);
        assertThat(api.reserve(show, bob, key(), "A1").status()).isEqualTo(201);

        assertThat(cancel(id, alice).status()).isEqualTo(200); // stale retry
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":1");
        assertThat(api.reserve(show, alice, key(), "A1").status()).isEqualTo(409);
    }

    @Test
    void cancelFreesPerUserQuota() {
        String id = api.reserve(show, alice, key(), "A1", "A2").field("reservation_id");
        assertThat(api.reserve(show, alice, key(), "A3").status()).isEqualTo(409);
        cancel(id, alice);
        assertThat(api.reserve(show, alice, key(), "A3").status()).isEqualTo(201);
    }

    @Test
    void replayAfterCancelReturnsCancelledReservationAndBooksNothing() {
        String k = key();
        api.reserve(show, alice, k, "A1");
        String id = jdbc.queryForObject("SELECT id::text FROM reservations WHERE idempotency_key = ?", String.class, k);
        cancel(id, alice);
        var replay = api.reserve(show, alice, k, "A1");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.field("status")).isEqualTo("cancelled");
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":0");
    }

    @Test
    void cancelRacingRebookersEndsConsistent() throws Exception {
        String id = api.reserve(show, alice, key(), "A1").field("reservation_id");
        List<Supplier<ApiClient.Resp>> tasks = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> tasks.add(() -> cancel(id, alice)));
        IntStream.range(0, 50).forEach(i -> {
            String t = api.token("racer-" + i + "-" + UUID.randomUUID());
            tasks.add(() -> api.reserve(show, t, key(), "A1"));
        });
        java.util.Collections.shuffle(tasks);
        var codes = ConcurrencyTest.stampede(tasks);

        assertThat(codes.keySet()).containsOnly(200, 201, 409);
        assertThat(codes.get(200)).hasValue(20);
        assertThat(codes.getOrDefault(201, new java.util.concurrent.atomic.AtomicInteger()).get()).isLessThanOrEqualTo(1);
        // seat row and reservations agree: A1 is owned by at most one confirmed reservation, and that one is it
        Integer owners = jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN seats s ON s.reservation_id = r.id
                WHERE s.show_id = ?::uuid AND s.label = 'A1' AND r.status = 'confirmed' AND s.status = 'confirmed'
                """, Integer.class, show);
        Integer confirmedReservations = jdbc.queryForObject("""
                SELECT count(*) FROM reservations WHERE show_id = ?::uuid AND status = 'confirmed'
                """, Integer.class, show);
        assertThat(owners).isEqualTo(confirmedReservations);
        assertThat(api.get("/shows/" + show, null).body()).contains("\"reconciled\":true");
    }
}
