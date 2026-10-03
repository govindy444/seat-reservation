package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.seats.support.ApiClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationTest {

    @LocalServerPort
    int port;

    ApiClient api;
    String show;
    String alice;
    String bob;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        show = api.createShow("""
                {"name":"t","seats":["A1","A2","A3","A4"],"price_paise":25000}""");
        alice = api.token("alice");
        bob = api.token("bob");
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void reservesSeatAndChargesIntegerPaise() {
        var r = api.reserve(show, alice, key(), "A1", "A2");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body()).contains("\"user_id\":\"alice\"", "\"amount_paise\":50000", "\"status\":\"confirmed\"",
                "\"show_id\":\"" + show + "\"");
        assertThat(api.get("/shows/" + show, null).body())
                .contains("\"available\":2", "\"confirmed\":2", "\"reconciled\":true");
    }

    @Test
    void takenSeatIsCleanConflict() {
        assertThat(api.reserve(show, alice, key(), "A1").status()).isEqualTo(201);
        var r = api.reserve(show, bob, key(), "A1");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.field("error")).isEqualTo("seat_taken");
    }

    @Test
    void multiSeatIsAllOrNothing() {
        assertThat(api.reserve(show, alice, key(), "A2").status()).isEqualTo(201);
        assertThat(api.reserve(show, bob, key(), "A1", "A2").status()).isEqualTo(409);
        // A1 must not have been taken by the failed request
        assertThat(api.reserve(show, bob, key(), "A1").status()).isEqualTo(201);
    }

    @Test
    void spoofedUserInBodyIsIgnored() {
        var r = api.post("/shows/" + show + "/reserve",
                "{\"seats\":[\"A3\"],\"idempotency_key\":\"" + key() + "\",\"user_id\":\"bob\"}", alice);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.field("user_id")).isEqualTo("alice");
    }

    @Test
    void badRequestsAre4xx() {
        assertThat(api.reserve(show, alice, key(), "Z9").status()).isEqualTo(400);
        assertThat(api.reserve(show, alice, key(), "A1", "A1").status()).isEqualTo(400);
        assertThat(api.reserve(UUID.randomUUID().toString(), alice, key(), "A1").status()).isEqualTo(404);
        assertThat(api.post("/shows/" + show + "/reserve", "{\"seats\":[\"A1\"]}", alice).status()).isEqualTo(400);
        assertThat(api.post("/shows/" + show + "/reserve", "{\"seats\":[]}", alice).status()).isEqualTo(400);
        assertThat(api.reserve(show, null, key(), "A1").status()).isEqualTo(401);
    }
}
