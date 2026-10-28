CREATE TABLE orders (
    id              uuid        PRIMARY KEY,
    -- The client's Idempotency-Key header. UNIQUE is what stops two concurrent retries from both creating an order.
    idempotency_key varchar(64) NOT NULL UNIQUE,
    sku             varchar(64) NOT NULL,
    quantity        integer     NOT NULL CHECK (quantity > 0),
    status          varchar(16) NOT NULL,
    reservation_id  uuid        NOT NULL,
    reserved_until  timestamptz NOT NULL
);

-- Events waiting to be sent to Kafka, written in the same transaction as the change they describe
CREATE TABLE outbox (
    id          bigserial    PRIMARY KEY,
    topic       varchar(128) NOT NULL,
    message_key varchar(64)  NOT NULL,
    payload     text         NOT NULL
);
