import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-sale stampede against a live deployment. Zero dependencies: run with any JDK 21+
 *
 *   java burst/Burst.java https://your-app.example.com
 *
 * Options (flags or env): --requests 20000  --hot-seats 5  --per-hot-seat 500  --concurrency 1000
 *                         --admin-key KEY (env ADMIN_KEY, default dev-admin-key)
 *
 * Phases: hot-seat storm (many users, same seat) -> mixed stampede with same-key retries and
 * same-key-different-seat abuse -> per-user-limit race -> identity spoofing -> reconciliation against the API
 * and /actuator/prometheus. Exits 1 if any correctness check fails.
 */
public class Burst {

    // ---------- config ----------
    static String base;
    static int totalRequests = 20_000;
    static int hotSeats = 5;
    static int perHotSeat = 500;
    static int concurrency = 1000;
    static String adminKey = System.getenv().getOrDefault("ADMIN_KEY", "dev-admin-key");

    static final HttpClient http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    static Semaphore inFlight;
    static final Random rnd = new Random(42);

    // ---------- result bookkeeping ----------
    record Resp(int status, String body, long micros, String requestId) {
        String field(String name) {
            Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        }

        int intField(String name) {
            Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(body);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        String outcome() {
            if (status < 0) {
                return "transport-error";
            }
            String err = field("error");
            return status + (err != null ? " " + err : status == 200 ? " replay" : status == 201 ? " confirmed" : "");
        }
    }

    static final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
    static final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> sampleErrors = new ConcurrentLinkedQueue<>();
    static final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        inFlight = new Semaphore(concurrency);
        System.out.printf("Burst against %s  (requests~%d, hot seats=%d x %d users, concurrency=%d)%n%n",
                base, totalRequests, hotSeats, perHotSeat, concurrency);

        Resp ready = send(get("/actuator/health/readiness"), false);
        System.out.println("readiness: " + ready.status());
        if (ready.status() != 200) {
            System.out.println("service not ready, aborting: " + ready.body());
            System.exit(1);
        }
        String metricsBefore = send(get("/actuator/prometheus"), false).body();

        String admin = token("burst-admin", adminKey);
        if (admin == null) {
            System.out.println("could not get admin token (check --admin-key / ADMIN_KEY)");
            System.exit(1);
        }

        // 20 rows x 25 = 500 seats; rows A-C are the "good" seats everyone wants
        List<String> seats = new ArrayList<>();
        for (char row = 'A'; row < 'A' + 20; row++) {
            for (int n = 1; n <= 25; n++) {
                seats.add(row + "" + n);
            }
        }
        String showId = createShow(admin, "burst-" + System.currentTimeMillis(), seats, 25_000, 4);
        System.out.println("show: " + showId + " (" + seats.size() + " seats, limit 4, 25000 paise)");

        int stampedeRequests = Math.max(0, totalRequests - hotSeats * perHotSeat);
        int stampedeUsers = Math.max(1, stampedeRequests / 4);
        System.out.printf("minting %d user tokens...%n", hotSeats * perHotSeat + stampedeUsers + 3);
        List<String> hotUsers = mintTokens("hot", hotSeats * perHotSeat);
        List<String> crowd = mintTokens("crowd", stampedeUsers);

        // seat -> reservation ids from every 2xx, to prove no seat ever went to two reservations
        Map<String, Set<String>> seatOwners = new ConcurrentHashMap<>();
        Map<String, String> reservationOwner = new ConcurrentHashMap<>();
        long t0 = System.nanoTime();

        // ---------- phase 1: hot-seat storm ----------
        System.out.printf("%n[1] hot-seat storm: %d users x %d seats, all at once%n", perHotSeat, hotSeats);
        Map<String, Map<Integer, AtomicInteger>> hotCodes = new ConcurrentHashMap<>();
        List<Supplier<Resp>> storm = new ArrayList<>();
        for (int s = 0; s < hotSeats; s++) {
            String seat = seats.get(s);
            hotCodes.put(seat, new ConcurrentHashMap<>());
            for (int u = 0; u < perHotSeat; u++) {
                String tok = hotUsers.get(s * perHotSeat + u);
                storm.add(() -> {
                    Resp r = reserve(showId, tok, UUID.randomUUID().toString(), List.of(seat));
                    hotCodes.get(seat).computeIfAbsent(r.status(), k -> new AtomicInteger()).incrementAndGet();
                    recordOwnership(r, seatOwners, reservationOwner);
                    return r;
                });
            }
        }
        Collections.shuffle(storm, rnd);
        stampede(storm);
        hotCodes.forEach((seat, codes) -> {
            int wins = codes.getOrDefault(201, new AtomicInteger()).get();
            System.out.printf("    %-4s %s%n", seat, new TreeMap<>(codes));
            check(wins == 1, "hot seat " + seat + " had " + wins + " winners (expected exactly 1)");
        });

        // ---------- phase 2: stampede with retries ----------
        System.out.printf("%n[2] stampede: ~%d reserves from %d users, 10%% sent twice with the same key, "
                + "1%% reuse a key for different seats%n", stampedeRequests, crowd.size());
        List<String> good = seats.subList(hotSeats, 75);
        List<String> rest = seats.subList(75, seats.size());
        List<Supplier<Resp>> wave = new ArrayList<>();
        AtomicInteger retryPairsBad = new AtomicInteger();
        AtomicInteger mismatchNot409 = new AtomicInteger();
        ConcurrentLinkedQueue<Resp[]> retryPairs = new ConcurrentLinkedQueue<>();
        int planned = 0;
        while (planned < stampedeRequests) {
            String tok = crowd.get(rnd.nextInt(crowd.size()));
            List<String> want = pick(rnd.nextInt(10) < 7 ? good : rest, 1 + (rnd.nextInt(4) == 0 ? 1 : 0));
            String key = UUID.randomUUID().toString();
            int roll = rnd.nextInt(100);
            if (roll < 10 && planned + 2 <= stampedeRequests) {
                // same request twice, concurrently: must book at most once
                Resp[] pair = new Resp[2];
                retryPairs.add(pair);
                for (int i = 0; i < 2; i++) {
                    int idx = i;
                    wave.add(() -> {
                        Resp r = reserve(showId, tok, key, want);
                        pair[idx] = r;
                        recordOwnership(r, seatOwners, reservationOwner);
                        return r;
                    });
                }
                planned += 2;
            } else if (roll < 11 && planned + 2 <= stampedeRequests) {
                // same key, different seats, sequential: second must be 409 (or replay-mismatch) and book nothing
                List<String> other = pick(rest, 1);
                wave.add(() -> {
                    Resp first = reserve(showId, tok, key, want);
                    recordOwnership(first, seatOwners, reservationOwner);
                    Resp second = reserve(showId, tok, key, other);
                    recordOwnership(second, seatOwners, reservationOwner);
                    if (first.status() / 100 == 2 && second.status() != 409) {
                        mismatchNot409.incrementAndGet();
                    }
                    return second;
                });
                planned += 2;
            } else {
                wave.add(() -> {
                    Resp r = reserve(showId, tok, key, want);
                    recordOwnership(r, seatOwners, reservationOwner);
                    return r;
                });
                planned += 1;
            }
        }
        Collections.shuffle(wave, rnd);
        stampede(wave);
        for (Resp[] pair : retryPairs) {
            Resp a = pair[0], b = pair[1];
            int creates = (a.status() == 201 ? 1 : 0) + (b.status() == 201 ? 1 : 0);
            boolean sameId = a.status() / 100 != 2 || b.status() / 100 != 2
                    || a.field("reservation_id").equals(b.field("reservation_id"));
            if (creates > 1 || !sameId) {
                retryPairsBad.incrementAndGet();
            }
        }
        check(retryPairsBad.get() == 0, retryPairsBad + " same-key retry pairs booked twice");
        check(mismatchNot409.get() == 0, mismatchNot409 + " same-key-different-seats requests were not 409");
        System.out.printf("    retry pairs: %d, all booked at most once: %s%n", retryPairs.size(), retryPairsBad.get() == 0);

        double seconds = (System.nanoTime() - t0) / 1e9;

        // ---------- phase 3: per-user limit race ----------
        System.out.println("\n[3] per-user limit: one user, 10 parallel single-seat reserves, limit 4");
        String limitShow = createShow(admin, "burst-limit-" + System.currentTimeMillis(),
                List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10"), 100, 4);
        String limitUser = token("burst-limit-" + UUID.randomUUID(), null);
        AtomicInteger limitWins = new AtomicInteger();
        List<Supplier<Resp>> limitTasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String seat = "L" + i;
            limitTasks.add(() -> {
                Resp r = reserve(limitShow, limitUser, UUID.randomUUID().toString(), List.of(seat));
                if (r.status() == 201) {
                    limitWins.incrementAndGet();
                }
                return r;
            });
        }
        stampede(limitTasks);
        Resp limitState = send(get("/shows/" + limitShow), false);
        int limitConfirmed = countOf(limitState.body(), "confirmed");
        System.out.printf("    201s: %d, confirmed seats on show: %d%n", limitWins.get(), limitConfirmed);
        check(limitWins.get() <= 4 && limitConfirmed <= 4, "per-user limit exceeded: " + limitConfirmed + " seats");

        // ---------- phase 4: identity ----------
        System.out.println("\n[4] identity: spoofed user_id in body, cancel someone else's reservation");
        String alice = token("burst-alice-" + UUID.randomUUID(), null);
        String mallory = token("burst-mallory-" + UUID.randomUUID(), null);
        Resp spoof = send(post("/shows/" + limitShow + "/reserve",
                "{\"seats\":[\"L10\"],\"idempotency_key\":\"" + UUID.randomUUID() + "\",\"user_id\":\"someone-else\"}",
                mallory), false);
        boolean spoofOk = spoof.status() == 409 /* L10 may be gone */
                || (spoof.status() == 201 && spoof.field("user_id").startsWith("burst-mallory-"));
        System.out.printf("    spoofed reserve -> %d user_id=%s%n", spoof.status(), spoof.field("user_id"));
        check(spoofOk, "spoofed body user_id was honoured");
        String someoneElses = reservationOwner.keySet().stream().findFirst().orElse(null);
        if (someoneElses != null) {
            Resp steal = send(post("/reservations/" + someoneElses + "/cancel", "", alice), false);
            System.out.printf("    cancel another user's reservation -> %d%n", steal.status());
            check(steal.status() == 404 || steal.status() == 403, "cancel of another user's reservation returned " + steal.status());
        }

        // ---------- reconciliation ----------
        System.out.println("\n[5] reconciliation");
        Resp state = send(get("/shows/" + showId), false);
        int available = countOf(state.body(), "available");
        int held = countOf(state.body(), "held");
        int confirmed = countOf(state.body(), "confirmed");
        int total = state.intField("total_seats");
        long doubleSold = seatOwners.values().stream().filter(s -> s.size() > 1).count();
        int seatsFrom201 = seatOwners.size();
        System.out.printf("    API: available=%d held=%d confirmed=%d total=%d -> %s%n", available, held, confirmed,
                total, available + held + confirmed == total ? "RECONCILED" : "MISMATCH");
        System.out.printf("    seats confirmed per 2xx responses: %d, seats owned by >1 reservation: %d%n",
                seatsFrom201, doubleSold);
        check(available + held + confirmed == total, "available+held+confirmed != total_seats");
        check(doubleSold == 0, doubleSold + " seats were confirmed to more than one reservation");
        check(confirmed == seatsFrom201, "API confirmed=" + confirmed + " but clients saw " + seatsFrom201 + " seats confirmed");

        System.out.println("    waiting 6s for seat gauges to refresh...");
        Thread.sleep(6000);
        String metricsAfter = send(get("/actuator/prometheus"), false).body();
        double gAvail = metric(metricsAfter, "show_seats{show_id=\"" + showId + "\",status=\"available\"}");
        double gConf = metric(metricsAfter, "show_seats{show_id=\"" + showId + "\",status=\"confirmed\"}");
        double gHeld = metric(metricsAfter, "show_seats{show_id=\"" + showId + "\",status=\"held\"}");
        double gCap = metric(metricsAfter, "show_capacity{show_id=\"" + showId + "\"}");
        System.out.printf("    metrics: show_seats available=%.0f held=%.0f confirmed=%.0f capacity=%.0f%n", gAvail, gHeld, gConf, gCap);
        check(gConf == confirmed && gAvail == available && gAvail + gHeld + gConf == gCap,
                "metrics gauges disagree with API state");
        System.out.println("    counter deltas during this run (include any other traffic hitting the service):");
        for (String series : List.of("reservations_confirmed_total",
                "reservations_declined_total{reason=\"seat-taken\"}",
                "reservations_declined_total{reason=\"per-user-limit\"}",
                "reservations_declined_total{reason=\"idempotent-replay\"}",
                "reservations_declined_total{reason=\"idempotency-key-mismatch\"}")) {
            System.out.printf("      %-64s +%.0f%n", series, metric(metricsAfter, series) - metric(metricsBefore, series));
        }

        // ---------- summary ----------
        int all = outcomes.values().stream().mapToInt(AtomicInteger::get).sum();
        long fivexx = outcomes.entrySet().stream().filter(e -> e.getKey().startsWith("5"))
                .mapToLong(e -> e.getValue().get()).sum();
        long transport = outcomes.getOrDefault("transport-error", new AtomicInteger()).get();
        System.out.printf("%n==== outcome distribution (%d reserve requests, phases 1-2 in %.1fs = %.0f req/s) ====%n",
                all, seconds, (hotSeats * perHotSeat + stampedeRequests) / seconds);
        new TreeMap<>(outcomes).forEach((k, v) -> System.out.printf("  %-34s %7d%n", k, v.get()));
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        if (!sorted.isEmpty()) {
            System.out.printf("  latency ms: p50=%.0f p95=%.0f p99=%.0f max=%.0f%n", pct(sorted, 50), pct(sorted, 95),
                    pct(sorted, 99), sorted.get(sorted.size() - 1) / 1000.0);
        }
        System.out.printf("  5xx: %d   transport errors: %d%n", fivexx, transport);
        sampleErrors.stream().limit(5).forEach(e -> System.out.println("    e.g. " + e));
        check(fivexx == 0, fivexx + " responses were 5xx");
        check(transport == 0, transport + " requests failed at the transport level (timeouts/resets)");

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println("FAILED CHECKS:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    // ---------- API helpers ----------
    static String token(String user, String key) {
        String body = "{\"user_id\":\"" + user + "\"" + (key != null ? ",\"admin_key\":\"" + key + "\"" : "") + "}";
        return send(post("/auth/token", body, null), false).field("access_token");
    }

    static List<String> mintTokens(String prefix, int n) throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36);
        String[] out = new String[n];
        List<Supplier<Resp>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            tasks.add(() -> {
                Resp r = send(post("/auth/token", "{\"user_id\":\"" + prefix + "-" + run + "-" + idx + "\"}", null), false);
                out[idx] = r.field("access_token");
                return r;
            });
        }
        stampede(tasks);
        for (String t : out) {
            if (t == null) {
                throw new IllegalStateException("token minting failed");
            }
        }
        return List.of(out);
    }

    static String createShow(String admin, String name, List<String> seats, long price, int limit) {
        String list = String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList());
        Resp r = send(post("/shows", "{\"name\":\"" + name + "\",\"seats\":[" + list + "],\"price_paise\":" + price
                + ",\"per_user_limit\":" + limit + "}", admin), false);
        if (r.status() != 201) {
            throw new IllegalStateException("create show failed: " + r.status() + " " + r.body());
        }
        return r.field("id");
    }

    static Resp reserve(String showId, String token, String key, List<String> seats) {
        String list = String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList());
        return send(post("/shows/" + showId + "/reserve", "{\"seats\":[" + list + "],\"idempotency_key\":\"" + key + "\"}",
                token), true);
    }

    static void recordOwnership(Resp r, Map<String, Set<String>> seatOwners, Map<String, String> reservationOwner) {
        if (r.status() == 201 || r.status() == 200) {
            String id = r.field("reservation_id");
            if (id == null || !"confirmed".equals(r.field("status"))) {
                return;
            }
            reservationOwner.put(id, r.field("user_id"));
            Matcher m = Pattern.compile("\"seats\":\\[([^\\]]*)\\]").matcher(r.body());
            if (m.find()) {
                for (String s : m.group(1).replace("\"", "").split(",")) {
                    seatOwners.computeIfAbsent(s, k -> ConcurrentHashMap.newKeySet()).add(id);
                }
            }
        }
    }

    // ---------- HTTP plumbing ----------
    static HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).GET();
    }

    static HttpRequest.Builder post(String path, String json, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }

    static Resp send(HttpRequest.Builder b, boolean record) {
        String requestId = "burst-" + UUID.randomUUID().toString().substring(0, 13);
        HttpRequest req = b.header("X-Request-Id", requestId).timeout(Duration.ofSeconds(90)).build();
        long start = System.nanoTime();
        Resp r;
        try {
            inFlight.acquire();
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                r = new Resp(res.statusCode(), res.body(), (System.nanoTime() - start) / 1000, requestId);
            } finally {
                inFlight.release();
            }
        } catch (Exception e) {
            r = new Resp(-1, e.toString(), (System.nanoTime() - start) / 1000, requestId);
        }
        if (record) {
            outcomes.computeIfAbsent(r.outcome(), k -> new AtomicInteger()).incrementAndGet();
            latencies.add(r.micros());
            if ((r.status() >= 500 || r.status() < 0) && sampleErrors.size() < 20) {
                sampleErrors.add(r.status() + " request_id=" + requestId + " " + r.body().substring(0, Math.min(160, r.body().length())));
            }
        }
        return r;
    }

    /** Releases every task at the same instant (bounded by --concurrency in flight) and waits for all. */
    static void stampede(List<Supplier<Resp>> tasks) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Supplier<Resp> t : tasks) {
                pool.submit(() -> {
                    gate.await();
                    return t.get();
                });
            }
            gate.countDown();
        }
    }

    // ---------- misc ----------
    static List<String> pick(List<String> from, int n) {
        List<String> copy = new ArrayList<>(from);
        Collections.shuffle(copy, rnd);
        return new ArrayList<>(copy.subList(0, n));
    }

    static int countOf(String showJson, String status) {
        Matcher m = Pattern.compile("\"counts\":\\{[^}]*\"" + status + "\":(\\d+)").matcher(showJson);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static double metric(String exposition, String series) {
        for (String line : exposition.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Double.parseDouble(line.substring(series.length() + 1).trim());
            }
        }
        return 0;
    }

    static double pct(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1)) / 1000.0;
    }

    static void check(boolean ok, String failure) {
        if (!ok) {
            failures.add(failure);
        }
    }

    static void parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.isEmpty()) {
            System.err.println("usage: java burst/Burst.java <BASE_URL> [--requests N] [--hot-seats N] "
                    + "[--per-hot-seat N] [--concurrency N] [--admin-key KEY]");
            System.exit(2);
        }
        base = positional.get(0).replaceAll("/+$", "");
        totalRequests = Integer.parseInt(opts.getOrDefault("requests", "" + totalRequests));
        hotSeats = Integer.parseInt(opts.getOrDefault("hot-seats", "" + hotSeats));
        perHotSeat = Integer.parseInt(opts.getOrDefault("per-hot-seat", "" + perHotSeat));
        concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "" + concurrency));
        adminKey = opts.getOrDefault("admin-key", adminKey);
    }
}
