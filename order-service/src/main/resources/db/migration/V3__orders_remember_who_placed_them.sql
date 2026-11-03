-- The customer's username when they ordered, so admins see a name instead of a Keycloak user ID.
-- Orders placed before this have none and show the ID.
ALTER TABLE orders ADD COLUMN customer_name varchar(255);
