-- An order is now a cart: one or more lines, each with its own inventory reservation
CREATE TABLE order_line (
    order_id       uuid        NOT NULL REFERENCES orders (id),
    line_no        integer     NOT NULL,
    sku            varchar(64) NOT NULL,
    quantity       integer     NOT NULL CHECK (quantity > 0),
    reservation_id uuid        NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

-- Every order so far had exactly one product
INSERT INTO order_line (order_id, line_no, sku, quantity, reservation_id)
SELECT id, 0, sku, quantity, reservation_id FROM orders;

ALTER TABLE orders DROP COLUMN sku, DROP COLUMN quantity, DROP COLUMN reservation_id;
