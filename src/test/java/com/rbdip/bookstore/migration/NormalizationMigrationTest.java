package com.rbdip.bookstore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class NormalizationMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bookstore_normalization")
            .withUsername("bookstore")
            .withPassword("bookstore");

    @Test
    void normalizesPopulatedLegacySchemaWithoutLosingOrderData() {
        PGSimpleDataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "1").migrate();
        seedLegacyData(jdbc);
        List<OrderSnapshot> originalOrders = orderSnapshots(jdbc, false);
        List<ItemSnapshot> originalItems = itemSnapshots(jdbc, false);
        List<Long> originalProducts = jdbc.queryForList("SELECT id FROM products ORDER BY id", Long.class);

        flyway(dataSource, "2").migrate();

        assertThat(orderSnapshots(jdbc, true)).isEqualTo(originalOrders);
        assertThat(itemSnapshots(jdbc, true)).isEqualTo(originalItems);
        assertThat(jdbc.queryForList("SELECT id FROM products ORDER BY id", Long.class))
                .containsAll(originalProducts).hasSize(6);
        assertCustomerIdentity(jdbc);
        assertProductMapping(jdbc);
        assertNormalizedSchema(jdbc);
        assertConstraints(jdbc);
        assertThat(flyway(dataSource, "2").migrate().migrationsExecuted).isZero();
    }

    private static void seedLegacyData(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO products (name, price, description) VALUES
                    ('Shared title', 10.00, 'Original product'),
                    ('Shared title', 10.00, 'Duplicate product'),
                    ('Shared title', 12.00, 'Different price'),
                    ('Unused product', 5.00, 'Never ordered');
                INSERT INTO orders (customer_full_name, customer_address, customer_phone, status, created_at) VALUES
                    ('Alex Smith', NULL, NULL, 'paid', '2024-01-01 12:34:56'),
                    ('Alex Smith', NULL, NULL, 'new', '2024-01-02 12:34:56'),
                    ('Alex Smith', 'First street', NULL, 'shipped', '2024-01-03 12:34:56'),
                    ('Alex Smith', 'First street', NULL, 'cancelled', '2024-01-04 12:34:56'),
                    ('Alex Smith', 'First street', '+70000000000', 'new', '2024-01-05 12:34:56'),
                    ('Other Customer', NULL, '+71111111111', 'new', '2024-01-06 12:34:56');
                INSERT INTO order_items (order_id, product_name, product_price, quantity) VALUES
                    (1, 'Shared title', 10.00, 2),
                    (2, 'Shared title', 12.00, 1),
                    (3, 'Missing product', 20.00, 3),
                    (4, 'Missing product', 20.00, 4),
                    (5, 'Shared title', 10.00, 1),
                    (1, 'Shared title', 7.50, 5);
                """);
    }

    private static void assertCustomerIdentity(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isEqualTo(4);
        List<Long> customers = jdbc.queryForList("SELECT customer_id FROM orders ORDER BY id", Long.class);
        assertThat(customers.get(0)).isEqualTo(customers.get(1));
        assertThat(customers.get(2)).isEqualTo(customers.get(3));
        assertThat(List.of(customers.get(0), customers.get(2), customers.get(4), customers.get(5)))
                .doesNotHaveDuplicates();
    }

    private static void assertProductMapping(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT product_id FROM order_items WHERE id = 1", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT product_id FROM order_items WHERE id = 2", Long.class))
                .isEqualTo(3L);
        List<Long> missing = jdbc.queryForList(
                "SELECT product_id FROM order_items WHERE id IN (3, 4) ORDER BY id", Long.class);
        assertThat(missing).hasSize(2);
        assertThat(missing.get(0)).isEqualTo(missing.get(1));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM products
                WHERE description IS NULL
                  AND ((name = 'Missing product' AND price = 20.00)
                    OR (name = 'Shared title' AND price = 7.50))
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT description FROM products WHERE id = 2", String.class))
                .isEqualTo("Duplicate product");
    }

    private static void assertNormalizedSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'orders'
                """, String.class))
                .contains("customer_id")
                .doesNotContain("customer_full_name", "customer_address", "customer_phone");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'order_items'
                """, String.class))
                .contains("product_id").doesNotContain("product_name", "product_price");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class))
                .contains("idx_orders_customer_id", "idx_order_items_product_id", "idx_order_items_order_id");
    }

    private static void assertConstraints(JdbcTemplate jdbc) {
        List<String> invalidWrites = List.of(
                "UPDATE orders SET customer_id = -1 WHERE id = 1",
                "UPDATE order_items SET product_id = -1 WHERE id = 1",
                "UPDATE order_items SET order_id = -1 WHERE id = 1",
                "UPDATE orders SET customer_id = NULL WHERE id = 1",
                "UPDATE order_items SET product_id = NULL WHERE id = 1",
                "INSERT INTO customers (full_name, address, phone) VALUES ('Alex Smith', NULL, NULL)",
                "INSERT INTO customers (full_name, address, phone) VALUES ('Alex Smith', 'First street', NULL)");
        for (String sql : invalidWrites) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static List<OrderSnapshot> orderSnapshots(JdbcTemplate jdbc, boolean normalized) {
        String sql = normalized
                ? "SELECT o.id, c.full_name, c.address, c.phone, o.status, o.created_at"
                    + " FROM orders o JOIN customers c ON c.id = o.customer_id ORDER BY o.id"
                : "SELECT id, customer_full_name AS full_name, customer_address AS address,"
                    + " customer_phone AS phone, status, created_at FROM orders ORDER BY id";
        return jdbc.query(sql, (row, index) -> new OrderSnapshot(
                row.getLong("id"), row.getString("full_name"), row.getString("address"),
                row.getString("phone"), row.getString("status"),
                row.getTimestamp("created_at").toLocalDateTime()));
    }

    private static List<ItemSnapshot> itemSnapshots(JdbcTemplate jdbc, boolean normalized) {
        String sql = normalized
                ? "SELECT oi.id, oi.order_id, p.name, p.price, oi.quantity"
                    + " FROM order_items oi JOIN products p ON p.id = oi.product_id ORDER BY oi.id"
                : "SELECT id, order_id, product_name AS name, product_price AS price, quantity"
                    + " FROM order_items ORDER BY id";
        return jdbc.query(sql, (row, index) -> new ItemSnapshot(
                row.getLong("id"), row.getLong("order_id"), row.getString("name"),
                row.getBigDecimal("price"), row.getInt("quantity")));
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static Flyway flyway(PGSimpleDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(target).load();
    }

    private record OrderSnapshot(
            long id, String fullName, String address, String phone, String status, LocalDateTime createdAt) {
    }

    private record ItemSnapshot(long id, long orderId, String name, BigDecimal price, int quantity) {
    }
}
