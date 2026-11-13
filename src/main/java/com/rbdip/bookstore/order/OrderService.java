package com.rbdip.bookstore.order;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates order creation within one transaction. */
@Service
public class OrderService {

    private static final String DEFAULT_CUSTOMER_TYPE = "regular";

    private final OrderRequestValidator validator;
    private final OrderItemResolver itemResolver;
    private final PricingCalculator pricingCalculator;
    private final OrderPersistence persistence;
    private final OrderConfirmationNotifier confirmationNotifier;

    public OrderService(
            OrderRequestValidator validator,
            OrderItemResolver itemResolver,
            PricingCalculator pricingCalculator,
            OrderPersistence persistence,
            OrderConfirmationNotifier confirmationNotifier) {
        this.validator = validator;
        this.itemResolver = itemResolver;
        this.pricingCalculator = pricingCalculator;
        this.persistence = persistence;
        this.confirmationNotifier = confirmationNotifier;
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        validator.validate(request);
        List<PreparedOrderItem> items = itemResolver.resolve(request.items());
        List<PricingCalculator.LineItem> lineItems = items.stream()
                .map(PreparedOrderItem::toPricingLineItem)
                .toList();
        String customerType = request.customerType() == null ? DEFAULT_CUSTOMER_TYPE : request.customerType();
        BigDecimal total = pricingCalculator.calculateOrderTotal(lineItems, customerType, request.couponCode());
        Order order = persistence.save(request, items);
        confirmationNotifier.sendConfirmation(request.customerFullName(), order.getId(), total);
        return order;
    }
}
