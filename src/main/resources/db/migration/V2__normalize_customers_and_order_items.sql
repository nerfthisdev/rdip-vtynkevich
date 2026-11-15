-- Customer identity includes contact details; NULL contacts compare as equal.
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    phone VARCHAR(50),
    CONSTRAINT uk_customers_identity UNIQUE NULLS NOT DISTINCT (full_name, address, phone)
);

INSERT INTO customers (full_name, address, phone)
SELECT DISTINCT customer_full_name, customer_address, customer_phone
FROM orders;

ALTER TABLE orders ADD COLUMN customer_id BIGINT;

UPDATE orders AS o
SET customer_id = c.id
FROM customers AS c
WHERE c.full_name = o.customer_full_name
  AND c.address IS NOT DISTINCT FROM o.customer_address
  AND c.phone IS NOT DISTINCT FROM o.customer_phone;

ALTER TABLE orders
    ALTER COLUMN customer_id SET NOT NULL,
    ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    DROP COLUMN customer_full_name,
    DROP COLUMN customer_address,
    DROP COLUMN customer_phone;

-- V1 stored only product snapshots, so the original product ID is unrecoverable.
-- Reuse the smallest matching ID, or preserve an unmatched snapshot as a product.
INSERT INTO products (name, price, description)
SELECT DISTINCT oi.product_name, oi.product_price, NULL
FROM order_items AS oi
WHERE NOT EXISTS (
    SELECT 1 FROM products AS p
    WHERE p.name = oi.product_name AND p.price = oi.product_price
);

ALTER TABLE order_items ADD COLUMN product_id BIGINT;

UPDATE order_items AS oi
SET product_id = (
    SELECT MIN(p.id) FROM products AS p
    WHERE p.name = oi.product_name AND p.price = oi.product_price
);

ALTER TABLE order_items
    ALTER COLUMN product_id SET NOT NULL,
    ADD CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    DROP COLUMN product_name,
    DROP COLUMN product_price;

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
