package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.seats.support.ApiClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    /** Fires all tasks at once behind a start gate and tallies status codes. */
    Map<Integer, AtomicInteger> stampede(List<java.util.function.Supplier<ApiClient.Resp>> tasks) throws Exception {
        Map<Integer, AtomicInteger> codes = new ConcurrentHashMap<>();
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(t -> pool.submit(() -> {
                gate.await();
                codes.computeIfAbsent(t.get().status(), c -> new AtomicInteger()).incrementAndGet();
                return null;
            })).toList();
            gate.countDown();
            for (var f : futures) {
                f.get();
            }
        }
        return codes;
    }

    @Test
    void hotSeatStormHasExactlyOneWinner() throws Exception {
        String show = api.createShow("""
                {"name":"hot","seats":["A12","A13"],"price_paise":25000}""");
        int users = 300;
        List<String> tokens = IntStream.range(0, users).mapToObj(i -> api.token("storm-" + i)).toList();

        var codes = stampede(tokens.stream()
                .<java.util.function.Supplier<ApiClient.Resp>>map(t -> () -> api.reserve(show, t, UUID.randomUUID().toString(), "A12"))
                .toList());

        assertThat(codes.get(201)).hasValue(1);
        assertThat(codes.get(409)).hasValue(users - 1);
        assertThat(codes.keySet()).containsOnly(201, 409);
        assertThat(api.get("/shows/" + show, null).body())
                .contains("\"available\":1", "\"confirmed\":1", "\"reconciled\":true");
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrDoubleSell() throws Exception {
        List<String> labels = IntStream.rangeClosed(1, 20).mapToObj(i -> "B" + i).toList();
        String show = api.createShow("{\"name\":\"multi\",\"seats\":[" +
                String.join(",", labels.stream().map(l -> "\"" + l + "\"").toList()) + "],\"price_paise\":100}");

        // 200 users each want 3 consecutive seats, half of them listing the seats in reverse order
        List<java.util.function.Supplier<ApiClient.Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String token = api.token("multi-" + i);
            int start = i % 18;
            List<String> want = new ArrayList<>(labels.subList(start, start + 3));
            if (i % 2 == 1) {
                java.util.Collections.reverse(want);
            }
            String[] seats = want.toArray(String[]::new);
            tasks.add(() -> api.reserve(show, token, UUID.randomUUID().toString(), seats));
        }
        var codes = stampede(tasks);

        assertThat(codes.keySet()).containsOnly(201, 409);
        // every confirmed seat belongs to exactly one reservation, and every winning reservation got all 3 seats
        Integer orphans = jdbc.queryForObject("""
                SELECT count(*) FROM reservations r
                WHERE r.show_id = ?::uuid AND r.status = 'confirmed'
                  AND (SELECT count(*) FROM seats s WHERE s.reservation_id = r.id) <> cardinality(r.seats)
                """, Integer.class, show);
        assertThat(orphans).isZero();
        Integer confirmedSeats = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ?::uuid AND status = 'confirmed'", Integer.class, show);
        assertThat(confirmedSeats).isEqualTo(codes.get(201).get() * 3);
        assertThat(api.get("/shows/" + show, null).body()).contains("\"reconciled\":true");
    }
}
