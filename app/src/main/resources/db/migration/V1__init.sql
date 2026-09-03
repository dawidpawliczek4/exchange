CREATE TABLE users
(
    id         BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE credentials
(
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    user_id       BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE sessions
(
    id         BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE
);


CREATE TABLE candles
(
    interval_seconds INT    NOT NULL,
    bucket_start     BIGINT NOT NULL,
    open             BIGINT NOT NULL,
    high             BIGINT NOT NULL,
    low              BIGINT NOT NULL,
    close            BIGINT NOT NULL,
    volume           BIGINT NOT NULL,
    trade_count      INT    NOT NULL,
    last_seq         BIGINT NOT NULL,
    PRIMARY KEY (interval_seconds, bucket_start)
);