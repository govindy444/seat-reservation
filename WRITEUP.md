# Write-up

## 1. The atomic decision

**Where it lives:** one Postgres transaction in `ReservationService.doReserve`. Within it, locks are always taken in the same global order:

```
pg_advisory_xact_lock(hash(show_id:user_id))           -- 1. serialize this user's work on this show
SELECT … FROM reservations WHERE (user_id, key) = …    -- 2. idempotency lookup (replay or 409)
SELECT count(*) FROM seats WHERE show_id=? AND user_id=? -- 3. per-user limit, counted under the user lock
SELECT label, status FROM seats
  WHERE show_id=? AND label = ANY(?) ORDER BY label FOR UPDATE  -- 4. row-lock requested seats in label order
INSERT INTO reservations …                              -- 5. the idempotency record = the reservation
UPDATE seats SET status='confirmed', reservation_id=?, user_id=?
  WHERE show_id=? AND label = ANY(?) AND status='available'     -- 6. guarded claim; must touch exactly N rows
COMMIT
```

**Why it can't double-sell:**

- A seat's ownership lives in exactly one place: its row in `seats`, keyed by `(show_id, label)`. Two owners can't exist because there's only one row to write.
- Step 4 takes a row lock, so two transactions for A12 queue on that row. The second one only reads A12 after the first has committed. It then sees `confirmed` and declines with 409.
- Step 6 is itself a conditional update (`AND status='available'`). Even if the lock step were removed, a second writer would match 0 rows. The code checks that `claimed == requested`; if not, it throws and rolls back. So there are two independent guards, and I never rely on a read-then-write.
- `CHECK ck_seat_owner` makes "available but owned" or "taken but ownerless" impossible to store.

**The reconciliation invariant holds by construction.** Seat rows are created once, with the show, and never inserted or deleted afterwards. Every state change is an UPDATE of a single row's `status`. So `available + held + confirmed == total_seats` is just `count(*)` of a fixed set of rows. `GET /shows/{id}` reads the show and its seats in one REPEATABLE READ snapshot, so the API response can't show a torn count either.

**Multi-seat requests and deadlock.** Every transaction acquires locks in one global order:

1. the (show, user) advisory lock
2. then seat rows sorted by label

The seat order is enforced by `ORDER BY label FOR UPDATE`. In the plan, Postgres's LockRows node sits above the Sort, so rows are locked in the order they're emitted. Cancel uses the same order: user lock, then the reservation row, then seats by label. Because every lock is taken in a consistent total order, there's no wait cycle, so no deadlock is possible. The concurrency test `overlappingMultiSeatRequestsNeverDeadlockOrDoubleSell` exercises exactly this.

A hash collision on the advisory lock only makes two unrelated users wait for each other. That costs latency but can't produce a wrong answer or a deadlock, because the advisory lock is always the first lock a transaction takes.

**Partial requests are all-or-nothing.** If any seat in the locked set isn't available, the whole transaction rolls back and returns 409 with the taken seats listed. This holds under concurrency because all N seats are locked before any decision is made.

**Fast decline (precheck).** Before opening a transaction, one lock-free read checks four things:

- the show exists
- the seat labels exist
- the seats are still `available` in committed data
- whether this (user, key) has been seen

If a seat is *already committed as taken*, the request can't succeed, so it gets a 409 without taking a pooled connection for a lock wait. The precheck can only **decline**, never confirm. Declining on a committed "taken" is linearizable: the seat really was taken at some instant during the request. During a hot-seat storm, only the first wave (the requests that arrive before the winner commits) queues on A12's row lock. Everyone after that is declined by the precheck in one round trip. If the key has been seen, the precheck is skipped so the request goes on to replay.

## 2. Idempotency

- **Storage:** the `reservations` row *is* the idempotency record. `UNIQUE (user_id, idempotency_key)` means one key can produce at most one reservation per user, enforced by the database. Keys are scoped per user, so two users who happen to pick the same key don't collide, and one user can't replay or probe another user's key.
- **Exactly-once:** the lookup (step 2) runs under the user's advisory lock. Two concurrent retries of the same key are serialized. The second one finds the committed row from the first and replays it. There's a narrow case where the same key is used concurrently on *two different shows*, which means two different advisory locks. There the unique constraint is the backstop: the loser gets `DuplicateKeyException`, re-reads the winner's row, and replays it (or returns 409 if the body differs).
- **Same key, different body:** `request_hash = sha256(show_id | sorted(seats))` is stored with the reservation. On replay, a different hash gets `409 idempotency_key_mismatch`, and nothing is booked. Seat order doesn't matter: `["A2","A1"]` is the same request as `["A1","A2"]`.
- **Replay response:** a replay returns `200` with `Idempotent-Replayed: true` and the original body. So a storm with retries still shows exactly one `201` per seat, and a client can tell "I booked" apart from "this was already booked by my earlier attempt". A replay after a cancel returns the cancelled reservation and doesn't re-book.
- **Declines are not stored.** If a request was declined (seat taken, limit reached) and is retried with the same key, it's evaluated again. A seat that was freed in the meantime can therefore be booked by the retry. That's deliberate: the key promises at most one *booking*, not a frozen decline.
- **Metrics:** replays count under `reservations_declined_total{reason="idempotent-replay"}`, not under confirmed.

## 3. Holds and expiry

I chose **explicit cancel** (`POST /reservations/{id}/cancel`) over time-boxed holds. A reserve confirms directly, so `held` stays at 0 but remains in the schema and the API.

- **Owner-only:** the reservation is looked up and filtered by `user_id = token.sub`. Someone else's id returns 404, so you can't tell it apart from one that doesn't exist.
- **Can't resurrect a re-sold seat:** the release is `UPDATE seats SET status='available' … WHERE reservation_id = :this`. It frees only rows still owned by *this* reservation. If the rows disagree with the reservation, the cancel throws instead of guessing.
- **Idempotent and race-safe:** the reservation row is locked `FOR UPDATE`, and a second cancel sees `cancelled` and does nothing. The test `cancelRacingRebookersEndsConsistent` races a cancel against rebookers.
- Released seats are immediately re-bookable, and the user's quota drops because it's counted from `seats.user_id`.

**If I added holds:** `status='held'` with a `hold_expires_at` column, and confirmation as a guarded update `WHERE status='held' AND reservation_id=? AND hold_expires_at > now()`. Expiry would be a sweeper running `UPDATE … SET status='available' WHERE status='held' AND hold_expires_at < now()` with `FOR UPDATE SKIP LOCKED` batches. That shape means confirm and expire can't both win.

## 4. Consistency vs availability under a partition

This is a CP system. A single Postgres primary is the only authority on who owns a seat, so double-selling during a split brain is impossible by design.

- **App can't reach the DB:** readiness fails closed (a direct `SELECT 1` with a 2 s timeout, bypassing the pool), so the load balancer stops sending traffic. In-flight reserves get `503 + Retry-After`, not a guess. Nothing is sold from a cache.
- **Commit outcome unknown** (the connection drops mid-commit): the client gets a 503. It's safe to retry with the same key. If the commit landed, the retry replays it; if it didn't, the retry books.
- **Overload** (no pooled connection within 30 s): `503 + Retry-After: 1`, for the same reasons. I'd rather turn a buyer away explicitly than queue forever or answer wrongly.
- **What I give up:** during a DB outage, nobody can buy. For a seat inventory that's the right trade. An available-but-inconsistent design would have to oversell and then refund.

## 5. Observability: what pages me at 2am

| Alert | Signal | Why |
|---|---|---|
| **Reconciliation broken** | `sum by(show_id)(show_seats) != on(show_id) show_capacity` | It should be impossible. If it fires, the data model is broken. |
| **Invariant error in logs** | any `unhandled error` / `IllegalStateException` (`claimed X of Y`, `owns X of Y seats`) | The guards fired, which means something bypassed the locks. |
| **5xx on reserve** | `rate(http_server_requests_seconds_count{uri="/shows/{showId}/reserve",status=~"5.."}[1m]) > 0` | Every decline should be a 4xx. A 5xx means the DB is down, the pool is exhausted, or there's a bug. |
| **Not ready** | readiness `503` for more than 1 min | The DB is unreachable, so nobody can buy. |
| **Pool saturation** | `hikaricp_connections_pending` high and sustained, or p99 reserve latency > SLO | 503s are coming soon. Scale the DB, raise the pool size, or shed load. |

I'd look at these on a dashboard but not page on them: decline mix by reason (a spike in `idempotency-key-mismatch` means a buggy client), confirms per second, and `seats_confirmed_total − seats_released_total` against the `confirmed` gauge.

**Known limitations:**

- The seat gauges are refreshed from the DB every 5 s. During a burst they lag the API by up to 5 s, and converge exactly afterwards (the burst script checks this). I chose the delay so that a hot per-show counter row wouldn't serialize every reserve.
- Counters are per-process and reset on restart. The gauges are the durable truth.

## 6. AI usage

> **Fill this section in yourself.** The reviewers asked for specifics and honesty, and they'll test the depth live. Some prompts to answer concretely:
>
> - Which tool(s) did you use, and for what? (scaffolding, SQL, tests, burst script, dashboard, docs)
> - **Decided by you:** e.g. Postgres row locks vs. Redis, the lock order, all-or-nothing, cancel vs. holds, 200-on-replay, per-user key scope, CP over AP.
> - **Directed by you, written by AI:** which files or functions were mostly generated, and how you verified them (tests, reading the query plan, running the burst live).
> - Where the AI was wrong and you caught it (a concrete example is worth more than a paragraph).
> - That this README/WRITEUP was drafted with AI help from the code, then edited by you.
>
> A note on commit history: several commits share a timestamp or land minutes apart. If the work was done in larger sessions and committed afterwards in logical slices, say so here plainly. That reads much better than having a reviewer infer it.

## 7. What I'd do next

1. **Payment holds:** `held` with a TTL plus a `SKIP LOCKED` sweeper, and confirm-on-payment as a guarded transition (see §3).
2. **Live metrics during the burst:** compute `show_seats` from in-process deltas between DB refreshes, or refresh every 1 s for the active show only.
3. **Horizontal scale:** the app is stateless, so add replicas behind the LB. Postgres becomes the ceiling. After that: PgBouncer in transaction mode, then partition `seats` by `show_id`. For extreme hot shows, a per-show single-writer queue in front of the DB.
4. **Admission control at on-sale:** a virtual waiting room or token bucket per show, so 20k simultaneous requests become a bounded queue instead of 20k waits on the pool.
5. **Idempotency key TTL:** expire keys after N hours (a separate `idempotency_keys` table) so the unique index doesn't grow without bound.
6. **Real identity:** replace `/auth/token` with an external IdP (JWKS/RS256), keep `sub` as the only identity, and add rate limits per user and per IP.
7. **Tracing:** OpenTelemetry spans around the lock wait, so contention shows up in traces and not just as latency.
