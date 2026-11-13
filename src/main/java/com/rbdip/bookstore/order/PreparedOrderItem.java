package com.rbdip.bookstore.order;

import com.rbdip.bookstore.product.Product;

/** Keeps a resolved product and its validated quantity together. */
public record PreparedOrderItem(Product product, int quantity) {

    public PricingCalculator.LineItem toPricingLineItem() {
        return new PricingCalculator.LineItem(product.getPrice(), quantity);
    }
}
