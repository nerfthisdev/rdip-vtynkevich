package com.rbdip.bookstore.order;

import org.springframework.stereotype.Component;

/** Validates customer details and the presence of order items. */
@Component
public class OrderRequestValidator {

    public void validate(CreateOrderRequest request) {
        requireNonBlank(request.customerFullName(), "customerFullName");
        requireNonBlank(request.customerAddress(), "customerAddress");
        if (request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one item");
        }
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
