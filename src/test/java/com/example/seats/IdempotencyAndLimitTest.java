package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
class IdempotencyAndLimitTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;
    String show;
    String alice;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        String seats = String.join(",", IntStream.rangeClosed(1, 20).mapToObj(i -> "\"C" + i + "\"").toList());
        show = api.createShow("{\"name\":\"t\",\"seats\":[" + seats + "],\"price_paise\":1000}");
        alice = api.token("alice-" + UUID.randomUUID());
    }

    int reservationsFor(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE idempotency_key = ?", Integer.class, key);
    }

    @Test
    void retryWithSameKeyReplaysOriginal() {
        String key = UUID.randomUUID().toString();
        var first = api.reserve(show, alice, key, "C1", "C2");
        var retry = api.reserve(show, alice, key, "C2", "C1"); // same set, different order = same request
        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.field("reservation_id")).isEqualTo(first.field("reservation_id"));
        assertThat(reservationsFor(key)).isEqualTo(1);
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":2");
    }

    @Test
    void sameKeyDifferentSeatsIsRejected() {
        String key = UUID.randomUUID().toString();
        assertThat(api.reserve(show, alice, key, "C1").status()).isEqualTo(201);
        var r = api.reserve(show, alice, key, "C3");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.field("error")).isEqualTo("idempotency_key_mismatch");
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":1");
    }

    @Test
    void sameKeyOnDifferentShowIsRejected() {
        String other = api.createShow("{\"name\":\"o\",\"seats\":[\"C1\"],\"price_paise\":1000}");
        String key = UUID.randomUUID().toString();
        assertThat(api.reserve(show, alice, key, "C1").status()).isEqualTo(201);
        assertThat(api.reserve(other, alice, key, "C1").status()).isEqualTo(409);
    }

    @Test
    void keysAreScopedPerUser() {
        String key = UUID.randomUUID().toString();
        assertThat(api.reserve(show, alice, key, "C1").status()).isEqualTo(201);
        // bob using the same key string is a different request entirely; C1 is taken so he's declined, not replayed
        var bob = api.reserve(show, api.token("bob-" + UUID.randomUUID()), key, "C1");
        assertThat(bob.status()).isEqualTo(409);
        assertThat(bob.field("error")).isEqualTo("seat_taken");
    }

    @Test
    void parallelRetriesOfOneKeyBookOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Supplier<ApiClient.Resp>> tasks = IntStream.range(0, 50).<Supplier<ApiClient.Resp>>mapToObj(i -> () -> {
            var r = api.reserve(show, alice, key, "C5");
            if (r.status() < 300) {
                ids.add(r.field("reservation_id"));
            }
            return r;
        }).toList();
        var codes = ConcurrencyTest.stampede(tasks);

        assertThat(codes.get(201)).hasValue(1);
        assertThat(codes.get(200)).hasValue(49);
        assertThat(ids).hasSize(1);
        assertThat(reservationsFor(key)).isEqualTo(1);
    }

    @Test
    void perUserLimitHoldsUnderConcurrency() throws Exception {
        // 10 parallel single-seat reserves, distinct seats and keys, default limit 4
        List<Supplier<ApiClient.Resp>> tasks = IntStream.rangeClosed(1, 10).<Supplier<ApiClient.Resp>>mapToObj(
                i -> () -> api.reserve(show, alice, UUID.randomUUID().toString(), "C" + i)).toList();
        var codes = ConcurrencyTest.stampede(tasks);

        assertThat(codes.get(201)).hasValue(4);
        assertThat(codes.get(409)).hasValue(6);
        assertThat(api.get("/shows/" + show, null).body()).contains("\"confirmed\":4", "\"reconciled\":true");
    }

    @Test
    void limitCountsAcrossRequestsAndOversizedRequest() {
        assertThat(api.reserve(show, alice, UUID.randomUUID().toString(), "C1", "C2", "C3").status()).isEqualTo(201);
        var over = api.reserve(show, alice, UUID.randomUUID().toString(), "C4", "C5");
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.field("error")).isEqualTo("per_user_limit");
        assertThat(api.reserve(show, alice, UUID.randomUUID().toString(), "C4").status()).isEqualTo(201);

        String bob = api.token("bob-" + UUID.randomUUID());
        assertThat(api.reserve(show, bob, UUID.randomUUID().toString(), "C10", "C11", "C12", "C13", "C14").status())
                .isEqualTo(409);
        assertThat(api.reserve(show, bob, UUID.randomUUID().toString(), "C10", "C11", "C12", "C13").status())
                .isEqualTo(201);
    }

    @Test
    void invalidSeatIsRejectedAsInvalidEvenAtLimit() {
        assertThat(api.reserve(show, alice, UUID.randomUUID().toString(), "C1", "C2", "C3", "C4").status()).isEqualTo(201);
        var r = api.reserve(show, alice, UUID.randomUUID().toString(), "Z99");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.field("error")).isEqualTo("unknown_seat");
    }

    @Test
    void customLimitIsRespected() {
        String strict = api.createShow("{\"name\":\"s\",\"seats\":[\"D1\",\"D2\"],\"price_paise\":1,\"per_user_limit\":1}");
        assertThat(api.reserve(strict, alice, UUID.randomUUID().toString(), "D1").status()).isEqualTo(201);
        assertThat(api.reserve(strict, alice, UUID.randomUUID().toString(), "D2").status()).isEqualTo(409);
    }
}
