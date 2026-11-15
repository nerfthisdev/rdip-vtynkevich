package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.rbdip.bookstore.reference.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class NormalizedOrderApiTest extends AbstractIntegrationTest {

    private static final String CUSTOMER_NAME = "Anna Sidorova";
    private static final String CUSTOMER_ADDRESS = "SPb, Nevsky 10";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "+79990000000")
    void reusesCustomerForSequentialOrdersIncludingMissingPhone(String phone) {
        Long productId = createProduct();
        CreateOrderRequest request = request(CUSTOMER_ADDRESS, phone,
                List.of(new CreateOrderRequest.Item(productId, 1)));

        long firstOrderId = createOrder(request);
        long secondOrderId = createOrder(request);

        assertThat(secondOrderId).isNotEqualTo(firstOrderId);
        assertThat(countRows("customers")).isEqualTo(1);
        assertThat(countRows("orders")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT customer_id) FROM orders", Long.class)).isEqualTo(1L);
        Map<String, Object> customer = jdbcTemplate.queryForMap(
                "SELECT full_name, address, phone FROM customers");
        assertThat(customer).containsEntry("full_name", CUSTOMER_NAME)
                .containsEntry("address", CUSTOMER_ADDRESS)
                .containsEntry("phone", phone);
    }

    @Test
    void keepsCustomersWithSameNameAndDifferentAddressesSeparate() {
        Long productId = createProduct();
        List<CreateOrderRequest.Item> items = List.of(new CreateOrderRequest.Item(productId, 1));

        createOrder(request(CUSTOMER_ADDRESS, null, items));
        createOrder(request("Moscow, Lenina 1", null, items));

        assertThat(countRows("customers")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT customer_id) FROM orders", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList("SELECT full_name FROM customers", String.class))
                .containsExactlyInAnyOrder(CUSTOMER_NAME, CUSTOMER_NAME);
    }

    @Test
    void storesExactProductReferencesAndListsNormalizedOrder() {
        Long firstProductId = createProduct();
        Long secondProductId = createProduct();
        long orderId = createOrder(request(CUSTOMER_ADDRESS, null, List.of(
                new CreateOrderRequest.Item(secondProductId, 2),
                new CreateOrderRequest.Item(firstProductId, null))));

        assertThat(jdbcTemplate.queryForList(
                "SELECT product_id FROM order_items ORDER BY quantity", Long.class))
                .containsExactly(firstProductId, secondProductId);
        assertThat(jdbcTemplate.queryForList(
                "SELECT order_id FROM order_items", Long.class)).containsOnly(orderId);
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/orders", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode orders = response.getBody();
        assertThat(orders).isNotNull();
        assertThat(orders.isArray()).isTrue();
        assertThat(orders.size()).isEqualTo(1);
        JsonNode order = orders.get(0);
        assertThat(order.path("id").asLong()).isEqualTo(orderId);
        assertThat(order.path("customerFullName").asText()).isEqualTo(CUSTOMER_NAME);
        assertThat(order.path("status").asText()).isEqualTo("new");
        JsonNode items = order.path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.findValuesAsText("productName")).containsOnly("Refactoring");
        assertThat(items.findValuesAsText("quantity")).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void rejectsInvalidLaterItemBeforeWritingCustomerOrOrder() {
        Long productId = createProduct();
        CreateOrderRequest request = request(CUSTOMER_ADDRESS, null, List.of(
                new CreateOrderRequest.Item(productId, 1),
                new CreateOrderRequest.Item(productId, 0)));

        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/orders", request, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("error").asText()).isEqualTo("quantity must be positive");
        assertNoOrdersOrCustomers();
        assertThat(countRows("products")).isEqualTo(1);
    }

    @Test
    void rollsBackCustomerOrderAndEarlierItemWhenLaterItemCannotBeSaved() {
        Long productId = createProduct();
        jdbcTemplate.execute("ALTER TABLE order_items ADD CONSTRAINT test_reject_quantity CHECK (quantity <> 7)");
        try {
            CreateOrderRequest request = request(CUSTOMER_ADDRESS, null, List.of(
                    new CreateOrderRequest.Item(productId, 1),
                    new CreateOrderRequest.Item(productId, 7)));

            ResponseEntity<JsonNode> response = restTemplate.postForEntity("/orders", request, JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertNoOrdersOrCustomers();
            assertThat(countRows("products")).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE order_items DROP CONSTRAINT test_reject_quantity");
        }
    }

    private CreateOrderRequest request(String address, String phone, List<CreateOrderRequest.Item> items) {
        return new CreateOrderRequest(CUSTOMER_NAME, address, phone, "regular", null, items);
    }

    private Long createProduct() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/products", Map.of("name", "Refactoring", "price", "40.00"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("id").asLong();
    }

    private long createOrder(CreateOrderRequest request) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/orders", request, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("new");
        return response.getBody().path("id").asLong();
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertNoOrdersOrCustomers() {
        assertThat(countRows("customers")).isZero();
        assertThat(countRows("orders")).isZero();
        assertThat(countRows("order_items")).isZero();
    }
}
