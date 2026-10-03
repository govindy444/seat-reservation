package com.example.seats.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin HTTP client so tests exercise the real server stack (security filters, JSON, error mapping). */
public class ApiClient {

    public record Resp(int status, String body) {
        public String field(String name) {
            Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
            if (!m.find()) {
                throw new AssertionError("no field " + name + " in " + body);
            }
            return m.group(1);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    public String token(String userId) {
        return post("/auth/token", "{\"user_id\":\"" + userId + "\"}", null).field("access_token");
    }

    public String adminToken() {
        return post("/auth/token", "{\"user_id\":\"admin\",\"admin_key\":\"dev-admin-key\"}", null).field("access_token");
    }

    public Resp get(String path, String token) {
        return send(auth(HttpRequest.newBuilder(URI.create(base + path)), token).GET());
    }

    public Resp post(String path, String json, String token) {
        return send(auth(HttpRequest.newBuilder(URI.create(base + path)), token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private static HttpRequest.Builder auth(HttpRequest.Builder b, String token) {
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    private Resp send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
