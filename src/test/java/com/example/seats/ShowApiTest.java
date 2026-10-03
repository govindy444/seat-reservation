package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.seats.support.ApiClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShowApiTest {

    @LocalServerPort
    int port;

    ApiClient api;
    String admin;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        admin = api.adminToken();
    }

    @Test
    void createsShowWithAllSeatsAvailableInOrder() {
        var created = api.post("/shows", """
                {"name":"friday-night","seats":["A1","A2","A10"],"price_paise":25000}""", admin);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"price_paise\":25000", "\"per_user_limit\":4", "\"total_seats\":3")
                .contains("\"available\":3", "\"held\":0", "\"confirmed\":0", "\"reconciled\":true");
        // admin order preserved: A2 before A10
        assertThat(created.body().indexOf("\"A2\"")).isLessThan(created.body().indexOf("\"A10\""));

        var fetched = api.get("/shows/" + created.field("id"), null);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).contains("\"available\":3", "\"reconciled\":true");
    }

    @Test
    void rejectsBadInputWith4xxNot5xx() {
        assertThat(api.post("/shows", """
                {"name":"dup","seats":["A1","A1"],"price_paise":100}""", admin).status()).isEqualTo(400);
        assertThat(api.post("/shows", """
                {"name":"float","seats":["A1"],"price_paise":250.5}""", admin).status()).isEqualTo(400);
        assertThat(api.post("/shows", """
                {"name":"neg","seats":["A1"],"price_paise":-1}""", admin).status()).isEqualTo(400);
        assertThat(api.post("/shows", """
                {"name":"empty","seats":[],"price_paise":1}""", admin).status()).isEqualTo(400);
        assertThat(api.post("/shows", "not json", admin).status()).isEqualTo(400);
        assertThat(api.get("/shows/00000000-0000-0000-0000-000000000000", null).status()).isEqualTo(404);
        assertThat(api.get("/shows/not-a-uuid", null).status()).isEqualTo(400);
    }

    @Test
    void frameworkErrorsKeepTheir4xxStatus() {
        assertThat(api.get("/no-such-route", admin).status()).isEqualTo(404);
        assertThat(api.get("/actuator/nope", null).status()).isIn(401, 404);
        assertThat(api.get("/shows", admin).status()).isEqualTo(405);
        assertThat(api.post("/shows/" + java.util.UUID.randomUUID(), "{}", admin).status()).isIn(403, 405);
    }
}
