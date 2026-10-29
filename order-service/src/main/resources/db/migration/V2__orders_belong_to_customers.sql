-- Orders belong to the signed-in customer (the token's subject). Rows from before sign-in get an owner no one has.
ALTER TABLE orders ADD COLUMN customer_id varchar(255) NOT NULL DEFAULT 'nobody';
ALTER TABLE orders ALTER COLUMN customer_id DROP DEFAULT;

-- Idempotency keys are per customer. With one shared key space, sending someone else's key would return their order.
ALTER TABLE orders DROP CONSTRAINT orders_idempotency_key_key;
ALTER TABLE orders ADD UNIQUE (customer_id, idempotency_key);
