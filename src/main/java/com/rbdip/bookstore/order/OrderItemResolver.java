package com.rbdip.bookstore.order;

import com.rbdip.bookstore.product.Product;
import com.rbdip.bookstore.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Resolves product references and applies the order quantity rules. */
@Component
public class OrderItemResolver {

    private static final int DEFAULT_QUANTITY = 1;

    private final ProductRepository productRepository;

    public OrderItemResolver(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<PreparedOrderItem> resolve(List<CreateOrderRequest.Item> requestedItems) {
        List<PreparedOrderItem> items = new ArrayList<>();
        for (CreateOrderRequest.Item raw : requestedItems) {
            Product product = productRepository.findById(raw.productId())
                    .orElseThrow(() -> new IllegalArgumentException("product " + raw.productId() + " not found"));
            int quantity = raw.quantity() == null ? DEFAULT_QUANTITY : raw.quantity();
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            items.add(new PreparedOrderItem(product, quantity));
        }
        return items;
    }
}
