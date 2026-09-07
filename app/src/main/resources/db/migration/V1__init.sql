CREATE EXTENSION IF NOT EXISTS timescaledb;

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

CREATE TABLE trades
(
    ts             TIMESTAMPTZ NOT NULL,
    seq            BIGINT      NOT NULL,
    maker_order_id BIGINT      NOT NULL,
    maker_user_id  BIGINT      NOT NULL,
    taker_order_id BIGINT      NOT NULL,
    taker_user_id  BIGINT      NOT NULL,
    price          BIGINT      NOT NULL,
    quantity       BIGINT      NOT NULL,
    PRIMARY KEY (ts, seq)
);

SELECT create_hypertable('trades', by_range('ts', INTERVAL '1 day'));

CREATE MATERIALIZED VIEW candles_5s
    WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT time_bucket(INTERVAL '5 seconds', ts) AS bucket,
       first(price, seq)                     AS open,
       max(price)                            AS high,
       min(price)                            AS low,
       last(price, seq)                      AS close,
       sum(quantity)                         AS volume,
       sum(quantity * price)                 AS quote_volume,
       count(*)                              AS trade_count
FROM trades
GROUP BY bucket
WITH NO DATA;

SELECT add_continuous_aggregate_policy('candles_5s',
                                       start_offset => NULL,
                                       end_offset => INTERVAL '10 seconds',
                                       schedule_interval => INTERVAL '15 seconds');
