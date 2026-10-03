package com.example.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.seats.support.ApiClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTest {

    static final String SHOW = """
            {"name":"s","seats":["A1"],"price_paise":100}""";

    @LocalServerPort
    int port;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void identityComesFromTokenSubject() {
        var me = api.get("/me", api.token("alice"));
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.field("user_id")).isEqualTo("alice");
    }

    @Test
    void protectedRoutesRequireValidToken() {
        assertThat(api.get("/me", null).status()).isEqualTo(401);
        assertThat(api.get("/me", "garbage").status()).isEqualTo(401);
        assertThat(api.post("/shows", SHOW, null).status()).isEqualTo(401);
    }

    @Test
    void onlyAdminsCreateShows() {
        assertThat(api.post("/shows", SHOW, api.token("alice")).status()).isEqualTo(403);
        assertThat(api.post("/shows", SHOW, api.adminToken()).status()).isEqualTo(201);
    }

    @Test
    void wrongAdminKeyIsRejected() {
        var r = api.post("/auth/token", """
                {"user_id":"mallory","admin_key":"guess"}""", null);
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void invalidUserIdIsRejected() {
        assertThat(api.post("/auth/token", "{\"user_id\":\"\"}", null).status()).isEqualTo(400);
        assertThat(api.post("/auth/token", "{}", null).status()).isEqualTo(400);
    }

    @Test
    void forgedTokensAreRejected() {
        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        String claims = "{\"sub\":\"admin\",\"scope\":\"user admin\",\"exp\":" + exp + "}";
        // signed with someone else's secret
        assertThat(api.get("/me", hs256(claims, "attacker-secret-attacker-secret-attacker")).status()).isEqualTo(401);
        // alg=none
        String none = b64("{\"alg\":\"none\"}") + "." + b64(claims) + ".";
        assertThat(api.get("/me", none).status()).isEqualTo(401);
        // correctly signed but expired
        String expired = "{\"sub\":\"alice\",\"scope\":\"user\",\"exp\":" + (Instant.now().getEpochSecond() - 600) + "}";
        assertThat(api.get("/me", hs256(expired, "dev-only-secret-change-me-in-production-0123456789")).status()).isEqualTo(401);
    }

    private static String hs256(String claims, String secret) {
        try {
            String signingInput = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(claims);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
