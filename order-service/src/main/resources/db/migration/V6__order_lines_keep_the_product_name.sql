-- The product's name when the order was placed, so the order still reads right after the catalog renames or deletes
-- the product. text because the catalog sets no limit. Orders placed before this have none (NULL) and show the SKU.
ALTER TABLE order_line ADD COLUMN product_name text;
