CREATE TABLE IF NOT EXISTS trips (
    thread_id   VARCHAR(64) PRIMARY KEY,
    user_query  TEXT        NOT NULL,
    status      VARCHAR(32) NOT NULL,
    progress    TEXT        NOT NULL DEFAULT '[]',
    state_json  TEXT,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_trips_created ON trips (created_at DESC);
CREATE TABLE IF NOT EXISTS trip_reviews (
    id          BIGSERIAL PRIMARY KEY,
    thread_id   VARCHAR(64) NOT NULL REFERENCES trips (thread_id) ON DELETE CASCADE,
    approved    BOOLEAN     NOT NULL,
    feedback    TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS eval_runs (
    id          BIGSERIAL PRIMARY KEY,
    suite       VARCHAR(32) NOT NULL,
    total       INT         NOT NULL,
    passed      INT         NOT NULL,
    report_json TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)
