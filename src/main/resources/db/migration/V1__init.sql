-- Shows: one row per event. Money is integer paise (BIGINT), never floating point.
CREATE TABLE shows (
    id             UUID        PRIMARY KEY,
    name           TEXT        NOT NULL,
    price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
    per_user_limit INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reservations: the idempotency record IS the reservation.
-- UNIQUE (user_id, idempotency_key) makes "same key reserves twice" impossible at the storage layer.
CREATE TABLE reservations (
    id              UUID        PRIMARY KEY,
    show_id         UUID        NOT NULL REFERENCES shows (id),
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,          -- hash of (show_id, sorted seats) to detect same-key-different-body
    seats           TEXT[]      NOT NULL,
    amount_paise    BIGINT      NOT NULL CHECK (amount_paise >= 0),
    status          TEXT        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    CONSTRAINT uq_reservation_idem UNIQUE (user_id, idempotency_key)
);

-- Seats: one row per physical seat. A seat's ownership lives in exactly one place (this row),
-- so "who owns A12" can never have two answers. Row count per show never changes after creation,
-- which is what makes available + held + confirmed == total_seats hold by construction.
CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id),
    label          TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'available' CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID        REFERENCES reservations (id),
    user_id        TEXT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- an available seat has no owner; a taken seat always has one
    CONSTRAINT ck_seat_owner CHECK (
        (status = 'available' AND reservation_id IS NULL AND user_id IS NULL)
     OR (status <> 'available' AND reservation_id IS NOT NULL AND user_id IS NOT NULL))
);

-- per-user limit check: count seats a user currently holds in a show
CREATE INDEX ix_seats_show_user ON seats (show_id, user_id) WHERE user_id IS NOT NULL;
