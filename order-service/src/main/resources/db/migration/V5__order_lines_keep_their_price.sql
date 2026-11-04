-- What one unit cost when the order was placed, from the catalog. Later price changes don't touch past orders.
-- Unconstrained numeric keeps the catalog's price exactly. Orders placed before this have no price (NULL).
ALTER TABLE order_line ADD COLUMN unit_price numeric;
