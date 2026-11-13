package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rbdip.bookstore.product.Product;
import com.rbdip.bookstore.product.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long PRODUCT_ID = 7L;
    private static final Long MISSING_PRODUCT_ID = 8L;
    private static final Long ORDER_ID = 42L;
    private static final BigDecimal PRODUCT_PRICE = new BigDecimal("12.50");

    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderConfirmationNotifier notifier;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(
                new OrderRequestValidator(), new OrderItemResolver(productRepository),
                new PricingCalculator(), new OrderPersistence(orderRepository, orderItemRepository), notifier);
    }

    @Test
    void keepsRepeatedProductsAndDefaultsQuantityBeforeNotifying() {
        givenProduct();
        givenOrderCanBeSaved();
        CreateOrderRequest request = request(
                new CreateOrderRequest.Item(PRODUCT_ID, null), new CreateOrderRequest.Item(PRODUCT_ID, 2));

        Order order = service.createOrder(request);

        assertThat(order.getId()).isEqualTo(ORDER_ID);
        assertThat(order.getStatus()).isEqualTo("new");
        assertThat(order.getCustomerFullName()).isEqualTo(request.customerFullName());
        assertThat(order.getCustomerAddress()).isEqualTo(request.customerAddress());
        assertThat(order.getCustomerPhone()).isEqualTo(request.customerPhone());
        ArgumentCaptor<OrderItem> savedItems = ArgumentCaptor.forClass(OrderItem.class);
        InOrder writes = inOrder(orderRepository, orderItemRepository, notifier);
        writes.verify(orderRepository).save(any(Order.class));
        writes.verify(orderItemRepository, times(2)).save(savedItems.capture());
        writes.verify(notifier).sendConfirmation(request.customerFullName(), ORDER_ID, new BigDecimal("37.50"));
        writes.verifyNoMoreInteractions();
        assertThat(savedItems.getAllValues()).extracting(OrderItem::getQuantity).containsExactly(1, 2);
        assertThat(savedItems.getAllValues()).allSatisfy(item -> {
            assertThat(item.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(item.getProductName()).isEqualTo("Refactoring");
            assertThat(item.getProductPrice()).isEqualByComparingTo(PRODUCT_PRICE);
        });
    }

    @Test
    void rejectsInvalidLaterItemBeforeWritingAnything() {
        givenProduct();
        CreateOrderRequest request = request(
                new CreateOrderRequest.Item(PRODUCT_ID, 1), new CreateOrderRequest.Item(PRODUCT_ID, 0));

        assertThatThrownBy(() -> service.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("quantity must be positive");

        verifyNoInteractions(orderRepository, orderItemRepository, notifier);
    }

    @Test
    void reportsMissingProductBeforeInvalidQuantity() {
        when(productRepository.findById(MISSING_PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(request(new CreateOrderRequest.Item(MISSING_PRODUCT_ID, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("product " + MISSING_PRODUCT_ID + " not found");

        verifyNoInteractions(orderRepository, orderItemRepository, notifier);
    }

    @Test
    void doesNotNotifyWhenSavingLaterItemFails() {
        givenProduct();
        givenOrderCanBeSaved();
        IllegalStateException failure = new IllegalStateException("item persistence failed");
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(failure);
        CreateOrderRequest request = request(
                new CreateOrderRequest.Item(PRODUCT_ID, 1), new CreateOrderRequest.Item(PRODUCT_ID, 2));

        assertThatThrownBy(() -> service.createOrder(request)).isSameAs(failure);

        verifyNoInteractions(notifier);
    }

    private void givenProduct() {
        when(productRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(new Product("Refactoring", PRODUCT_PRICE, "Book")));
    }

    private void givenOrderCanBeSaved() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", ORDER_ID);
            return order;
        });
    }

    private CreateOrderRequest request(CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(
                "Ivan Petrov", "Moscow, Lenina 1", "+79990000000", null, null, List.of(items));
    }
}
