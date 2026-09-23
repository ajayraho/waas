-- ================================================================
-- Waitlist-as-a-Service — V1 schema
-- Applied by Flyway on Core startup (replaces the old Docker init.sql).
-- Incorporates ARCHITECTURE.md §21.13:
--   • waitlist.serving_capacity        (§21.1)
--   • waitlist_entry.queue_score       (§21.3)
--   • BUMPED removed from state enum   (§21.11)
--   • referral_credit.version dropped  (§21.4)
--   • (waitlist_id, state) index for recovery/reconciliation (§21.6)
-- ================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Global join-order sequence. Used as the *initial* queue_score only;
-- after that, queue_score is rewritten by rank-based bumps (§21.3).
-- Global (not per-waitlist) so a join never takes a row lock on a
-- per-waitlist counter — at the cost of sparse scores within a waitlist.
CREATE SEQUENCE waitlist_join_seq START 1 INCREMENT 1;


-- ----------------------------------------------------------------
-- TENANT
-- ----------------------------------------------------------------
CREATE TABLE tenant (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    api_key    VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- ----------------------------------------------------------------
-- WAITLIST — per-queue configuration.
--   serving_capacity — how many entries may hold a checkout window
--                      (RESERVED) at the same time. NOT inventory.
--   max_capacity     — max queue length (NULL = unlimited).
-- ----------------------------------------------------------------
CREATE TABLE waitlist (
    id                         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID         NOT NULL REFERENCES tenant(id),
    name                       VARCHAR(255) NOT NULL,
    description                TEXT,
    group_policy               VARCHAR(10)  NOT NULL DEFAULT 'PARTIAL'
                                   CHECK (group_policy IN ('STRICT', 'PARTIAL')),
    serving_capacity           INTEGER      NOT NULL DEFAULT 1 CHECK (serving_capacity > 0),
    reservation_window_seconds INTEGER      NOT NULL DEFAULT 600 CHECK (reservation_window_seconds > 0),
    bump_amount                INTEGER      NOT NULL DEFAULT 1 CHECK (bump_amount > 0),
    max_capacity               INTEGER      CHECK (max_capacity IS NULL OR max_capacity > 0),
    is_active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_waitlist_tenant ON waitlist(tenant_id);


-- ----------------------------------------------------------------
-- APP_USER ("user" is reserved in PostgreSQL)
-- ----------------------------------------------------------------
CREATE TABLE app_user (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);


-- ----------------------------------------------------------------
-- WAITLIST_GROUP — policy lives on waitlist.group_policy.
-- ----------------------------------------------------------------
CREATE TABLE waitlist_group (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    waitlist_id UUID        NOT NULL REFERENCES waitlist(id),
    created_by  UUID        NOT NULL REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_waitlist_group_waitlist ON waitlist_group(waitlist_id);


-- ----------------------------------------------------------------
-- WAITLIST_ENTRY — one row per queue slot.
--   individual join → one row, group_id NULL
--   STRICT group    → one row for the whole group (occupies ONE serving slot)
--   PARTIAL group   → one row per member, group_id set on each
--
-- join_sequence       — from waitlist_join_seq, returned via RETURNING.
-- queue_score         — the Redis ZSET score. Starts = join_sequence,
--                       rewritten by rank-based bumps (§21.3).
--                       Crash recovery replays THIS value.
-- total_bumps_applied — audit only: queue spots actually gained.
-- ----------------------------------------------------------------
CREATE TABLE waitlist_entry (
    id                     UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    waitlist_id            UUID             NOT NULL REFERENCES waitlist(id),
    user_id                UUID             NOT NULL REFERENCES app_user(id),
    group_id               UUID             REFERENCES waitlist_group(id),
    state                  VARCHAR(20)      NOT NULL DEFAULT 'WAITING'
                               CHECK (state IN ('WAITING','RESERVED','CONFIRMED','EXPIRED','CANCELLED')),
    join_sequence          BIGINT           NOT NULL DEFAULT nextval('waitlist_join_seq'),
    queue_score            DOUBLE PRECISION NOT NULL,
    total_bumps_applied    INTEGER          NOT NULL DEFAULT 0 CHECK (total_bumps_applied >= 0),

    reserved_at            TIMESTAMPTZ,
    reservation_expires_at TIMESTAMPTZ,
    confirmed_at           TIMESTAMPTZ,
    cancelled_at           TIMESTAMPTZ,

    -- Impossible states made unrepresentable at the database level
    CONSTRAINT chk_reserved_needs_expiry
        CHECK (state != 'RESERVED'  OR (reserved_at IS NOT NULL AND reservation_expires_at IS NOT NULL)),
    CONSTRAINT chk_confirmed_needs_timestamp
        CHECK (state != 'CONFIRMED' OR confirmed_at IS NOT NULL),
    CONSTRAINT chk_cancelled_needs_timestamp
        CHECK (state != 'CANCELLED' OR cancelled_at IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One active entry per user per waitlist; re-join allowed after a
-- terminal state. CONFIRMED is treated as still "occupying" the waitlist.
CREATE UNIQUE INDEX idx_waitlist_entry_active
    ON waitlist_entry(user_id, waitlist_id)
    WHERE state NOT IN ('CANCELLED', 'EXPIRED');

CREATE INDEX idx_waitlist_entry_user ON waitlist_entry(user_id);

-- Recovery + reconciliation: "all WAITING / RESERVED rows of waitlist X" (§21.6).
-- Leading column waitlist_id also serves plain per-waitlist lookups.
CREATE INDEX idx_waitlist_entry_waitlist_state
    ON waitlist_entry(waitlist_id, state);


-- ----------------------------------------------------------------
-- GROUP_MEMBER
-- ----------------------------------------------------------------
CREATE TABLE group_member (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id      UUID        NOT NULL REFERENCES waitlist_group(id),
    user_id       UUID        NOT NULL REFERENCES app_user(id),
    has_confirmed BOOLEAN     NOT NULL DEFAULT FALSE,
    confirmed_at  TIMESTAMPTZ,
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_member_confirmed_needs_timestamp
        CHECK (has_confirmed = FALSE OR confirmed_at IS NOT NULL),
    UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_member_user ON group_member(user_id);


-- ----------------------------------------------------------------
-- REFERRAL — its UNIQUE constraint doubles as the idempotency key
-- for the credit (§21.4): a retried referral cannot double-credit.
-- ----------------------------------------------------------------
CREATE TABLE referral (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    waitlist_id UUID        NOT NULL REFERENCES waitlist(id),
    referrer_id UUID        NOT NULL REFERENCES app_user(id),
    referee_id  UUID        NOT NULL REFERENCES app_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (waitlist_id, referrer_id, referee_id),
    CONSTRAINT chk_no_self_referral CHECK (referrer_id != referee_id)
);

CREATE INDEX idx_referral_referee ON referral(referee_id);


-- ----------------------------------------------------------------
-- REFERRAL_CREDIT — ledger, one row per (user, waitlist).
--   pending_credits       — banked, not yet spent (§21.4)
--   total_credits_earned  — lifetime, never decremented
--   total_credits_applied — queue spots actually gained from credits
-- All updates are atomic increments / conditional decrements.
-- No version column: the real race (apply vs bank) is decided in Lua.
-- ----------------------------------------------------------------
CREATE TABLE referral_credit (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    waitlist_id           UUID        NOT NULL REFERENCES waitlist(id),
    user_id               UUID        NOT NULL REFERENCES app_user(id),
    pending_credits       INTEGER     NOT NULL DEFAULT 0 CHECK (pending_credits >= 0),
    total_credits_earned  INTEGER     NOT NULL DEFAULT 0 CHECK (total_credits_earned >= 0),
    total_credits_applied INTEGER     NOT NULL DEFAULT 0 CHECK (total_credits_applied >= 0),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (waitlist_id, user_id),
    CONSTRAINT chk_credit_accounting
        CHECK (total_credits_applied + pending_credits <= total_credits_earned)
);
