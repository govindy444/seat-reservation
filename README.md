# Seat Reservation at Scale

A JSON HTTP service that sells assigned seats for a show and stays correct under an on-sale stampede:
no seat is ever sold twice, no user exceeds their per-show limit, and a retried request never books twice.

Stack: Java 21 · Spring Boot 4 (virtual threads) · PostgreSQL · Flyway · Micrometer/Prometheus · JWT (HS256).

| | |
|---|---|
| **Live URL** | https://seat-reservation-production-f2a6.up.railway.app |
| **Live dashboard** | `https://seat-reservation-production-f2a6.up.railway.app/dashboard` |
| **Metrics** | `https://seat-reservation-production-f2a6.up.railway.app/actuator/prometheus` |
| **Deployed commit** | `https://seat-reservation-production-f2a6.up.railway.app/actuator/info` |

---

## Quick start

### Run locally (Docker, one command)

```bash
make up          # docker compose up -d --build  →  app on :8080, Postgres 17
make logs        # follow structured JSON logs
make down        # stop and delete the DB volume
```

A clean checkout needs only Docker. The Dockerfile builds with the Maven wrapper inside the image, so no local JDK is required.

### Run the tests

```bash
make test        # ./mvnw -B test  (Testcontainers starts a real Postgres; Docker required)
```

The tests run against real Postgres (no in-memory fakes), including concurrent ones:

- hot-seat storm with exactly one winner
- overlapping multi-seat requests with no deadlock and no double-sell
- parallel retries of one key booking once
- the per-user limit holding under parallel requests
- a cancel racing rebookers
- metrics reconciling with the API

---

## One-command burst

Reproduces the on-sale stampede against any deployment and prints the outcome distribution plus a final reconciliation. It **exits non-zero if any correctness check fails**.

```bash
ADMIN_KEY=<key> ./burst.sh https://seat-reservation-production-f2a6.up.railway.app
# or
make burst BASE_URL=<BASE_URL> ARGS="--admin-key <key>"
```

| Option | Default | Meaning |
|---|---|---|
| `--requests` | 20000 | total reserve requests across phases 1–2 |
| `--hot-seats` | 5 | number of contested seats |
| `--per-hot-seat` | 500 | users storming each hot seat |
| `--concurrency` | 1000 | requests in flight at once |
| `--admin-key` | `$ADMIN_KEY` | admin key used to create the burst show |

It uses a local JDK 21+ if you have one (`java burst/Burst.java`, no dependencies). Otherwise it runs the same file in an `eclipse-temurin:21-jdk` container.

**Phases**

1. **Hot-seat storm.** 500 distinct users × 5 seats, all fired at once. Checks that each seat gets exactly one `201` and every other request gets a `409`.
2. **Stampede.** About 17,500 mixed reserves on a 500-seat show. 10% are sent twice concurrently with the same key, and 1% reuse a key with different seats. Checks that each retry pair booked at most once with the same `reservation_id`, and that a reused key with different seats gets a `409`.
3. **Per-user limit race.** One user fires 10 parallel single-seat reserves with limit 4. Checks that the user ends with 4 or fewer seats.
4. **Identity.** Sends a spoofed `user_id` in the body, then tries to cancel another user's reservation. Checks that the token's user is the one who acts, and that the cancel returns `404`.
5. **Reconciliation.** Checks that `available + held + confirmed == total_seats` from the API, that no seat appears in more than one 2xx response, and that the Prometheus gauges match the API.

`./demo.sh <BASE_URL>` walks through every API behaviour once, printing the expected and actual status for each step. It's handy for a quick smoke test after a deploy.

---

## API

All bodies are JSON with `snake_case` fields. Money is **integer paise**: fractional values are rejected, never truncated.
Every response carries an `X-Request-Id` header. You can send your own to correlate with the logs.

### Auth: `POST /auth/token`
This is a stand-in identity provider (there are no passwords; that's out of scope). It returns a signed JWT whose `sub` is the user id.
Supply `admin_key` to get the `admin` scope.

```bash
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
# {"access_token":"eyJ...","token_type":"Bearer","expires_in":86400,"user_id":"alice","roles":["user"]}
```

From that point on, identity comes **only** from the token. No request body has a user field, and unknown fields such as a spoofed `user_id` are ignored. `GET /me` echoes who the server thinks you are.

### Create a show: `POST /shows` (admin)
```json
{ "name": "friday-night", "seats": ["A1","A2","A3"], "price_paise": 25000, "per_user_limit": 4 }
```
`per_user_limit` is optional (default 4). The response is `201`: the show with every seat `available`.

### Reserve: `POST /shows/{id}/reserve` (user)
```json
{ "seats": ["A12"], "idempotency_key": "b7c1…" }
```
You can send the idempotency key in the `Idempotency-Key` header or in the body. If you send both, they must match.

| Outcome | Status | `error` |
|---|---|---|
| New reservation | **201** | – |
| Retry of the same key + same seats (nothing new booked) | **200**, header `Idempotent-Replayed: true` | – |
| Any requested seat already taken | **409** | `seat_taken` |
| Would exceed per-user limit | **409** | `per_user_limit` |
| Same key, different seats/show | **409** | `idempotency_key_mismatch` |
| Seat label not in this show | 400 | `unknown_seat` |
| Show doesn't exist | 404 | `not_found` |

**Multi-seat requests are all-or-nothing.** If any requested seat is taken, nothing is booked and you get a `409` that names the taken seats.

```json
{ "reservation_id": "…", "show_id": "…", "user_id": "alice", "seats": ["A12"], "amount_paise": 25000, "status": "confirmed" }
```

### Cancel: `POST /reservations/{id}/cancel` (owner only)
Releases the reservation's seats back to `available` and frees the user's quota. Cancelling is idempotent: cancelling twice returns the same cancelled reservation.
Someone else's reservation returns `404`, so you can't tell it apart from one that doesn't exist.

### Show state: `GET /shows/{id}` (public)
Returns each seat's status plus `counts: {available, held, confirmed, reconciled}`. All of it comes from a single snapshot (REPEATABLE READ), so the counts always match the seat list.

> **Holds:** this service uses the explicit-cancel model. A successful reserve goes straight to `confirmed`, so `held` is always 0. It's kept in the schema and the API for when payment holds are added (see WRITEUP.md → *What I'd do next*).

---

## Health, metrics, logs

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health/liveness` | Checks only that the JVM is up. It never checks the DB, so a DB outage can't put the app into a restart loop. |
| `GET /actuator/health/readiness` | Checks the JVM plus a **direct, non-pooled `SELECT 1` against Postgres** with a 2 s timeout. It fails closed (`503`) when the DB is unreachable. A saturated pool does not count as an outage. Railway's deploy healthcheck uses this endpoint. |
| `GET /actuator/prometheus` | Prometheus metrics. |
| `GET /dashboard` | A live page (1 s polling) showing confirms and declines by reason, HTTP 2xx/4xx/5xx counts, DB pool usage and per-show seat counts. It reads the same Micrometer registry as `/actuator/prometheus`. |

**Key metrics**

| Metric | Type | Notes |
|---|---|---|
| `reservations_confirmed_total` | counter | Incremented only after commit |
| `seats_confirmed_total` | counter | Seats sold by new reservations |
| `reservations_declined_total{reason}` | counter | `seat-taken`, `per-user-limit`, `idempotent-replay`, `idempotency-key-mismatch`, `unknown-seat`, `not-found`, `bad-request`. Every reason is pre-registered at 0. |
| `reservations_cancelled_total`, `seats_released_total` | counter | |
| `show_seats{show_id,status}` | gauge | Read from the `seats` table (the source of truth) for the 10 most recent shows, refreshed every 5 s |
| `show_capacity{show_id}` | gauge | `total_seats` |
| `http_server_requests_seconds_*` | histogram | Latency and status by route |
| `hikaricp_connections_{active,pending,max}` | gauge | DB pool pressure during a burst |

Reconciliation check in PromQL: `sum by (show_id) (show_seats) == on(show_id) show_capacity`

**Logs** are JSON lines on stdout in ECS format (`LOG_FORMAT=logstash` switches the schema). Every line carries a `request_id`.
Each request produces one `access` line (method, path, status, duration_ms). Each reserve or cancel produces one domain line (outcome, reason, show_id, user_id, seats, reservation_id, amount_paise).

---

## Deploying (Railway)

`railway.json` sets up a Dockerfile build, `/actuator/health/readiness` as the deploy healthcheck (180 s, which covers a cold start plus Flyway migrations), and restart-on-failure.

1. Create a project with a **PostgreSQL** service and this repo as a second service.
2. On the app service, set these variables:
   - `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`: reference the Postgres service's variables
   - `JWT_SECRET`: 32 bytes or more of random data
   - `ADMIN_KEY`: anything secret
   - optional: `DB_POOL_SIZE` (default 20)
3. Generate a public domain. `GET /actuator/info` shows which commit is live.

| Env var | Default | |
|---|---|---|
| `PORT` | 8080 | |
| `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD` | localhost / 5432 / seats / seats / seats | |
| `JWT_SECRET` | dev secret (logs a warning) | must be 32 bytes or more |
| `JWT_TTL` | 24h | |
| `ADMIN_KEY` | `dev-admin-key` | **change in any shared environment** |
| `DB_POOL_SIZE` | 20 | |
| `DB_CONNECTION_TIMEOUT_MS` | 30000 | how long a request waits for a pooled connection before getting a `503` |
| `LOG_FORMAT` | ecs | `ecs` or `logstash` |

---

## Repository layout

```
src/main/java/com/example/seats/
  reservation/   reserve + cancel: the atomic path (ReservationService, ReservationRepository)
  show/          create + read shows
  auth/          token issuance, /me
  observability/ metrics, request-id filter, DB readiness probe
  dashboard/     /dashboard/stats JSON for the live page
  api/           error mapping (domain 4xx vs transient 503 vs real 500)
src/main/resources/db/migration/   Flyway schema
burst/Burst.java                   zero-dependency load + correctness harness
demo.sh                            one-pass API walkthrough
```
