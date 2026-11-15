package com.rbdip.bookstore.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @ParameterizedTest(name = "price={0}, quantity={1}, customer={2}, coupon={3}: {4}")
    @CsvSource(nullValues = "NULL", textBlock = """
            10.00,    10, regular,   NULL,            100.00
            10.00,    11, regular,   NULL,            104.50
            100.00,    1, vip,       NULL,             90.00
            100.00,    1, wholesale, NULL,             85.00
            100.00,    1, regular,   SAVE10,           90.00
            100.00,    1, regular,   SAVE20PERCENT,    80.00
            100.00,   11, vip,       SAVE10,          930.50
            100.00,   11, wholesale, SAVE20PERCENT,   710.60
            100.00,    1, NULL,      NULL,            100.00
            100.00,    1, unknown,   UNKNOWN,         100.00
            100.00,    1, VIP,       save10,          100.00
            999.99,    1, regular,   NULL,            999.99
            1000.00,   1, regular,   NULL,           1000.00
            1000.01,   1, regular,   NULL,            980.01
            1010.00,   1, regular,   SAVE10,         1000.00
            1010.01,   1, regular,   SAVE10,          980.01
            1111.11,   1, vip,       NULL,           1000.00
            1111.12,   1, vip,       NULL,            980.01
            1250.00,   1, regular,   SAVE20PERCENT,  1000.00
            1250.01,   1, regular,   SAVE20PERCENT,   980.01
            200.00,   11, vip,       SAVE10,         1833.58
            0.00,      1, regular,   NULL,              0.00
            10.00,     0, regular,   NULL,              0.00
            10.00,    -1, regular,   NULL,              0.00
            -10.00,    1, regular,   NULL,              0.00
            -10.00,   -2, regular,   NULL,             20.00
            9.99,      1, regular,   SAVE10,            0.00
            10.00,     1, regular,   SAVE10,            0.00
            10.01,     1, regular,   SAVE10,            0.01
            1.004,     1, regular,   NULL,              1.00
            1.005,     1, regular,   NULL,              1.01
            1.006,     1, regular,   NULL,              1.01
            """)
    void preservesSingleLinePricingRules(
            String price, int quantity, String customerType, String couponCode, String expected) {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line(price, quantity)), customerType, couponCode);

        assertThat(total).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", textBlock = """
            regular,   NULL
            vip,       SAVE10
            wholesale, SAVE20PERCENT
            NULL,      NULL
            """)
    void emptyOrderAlwaysReturnsScaledZero(String customerType, String couponCode) {
        assertThat(calculator.calculateOrderTotal(List.of(), customerType, couponCode))
                .isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void appliesBulkDiscountToEachLineIndependently() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("10.00", 10), line("10.00", 11)), "regular", null);

        assertThat(total).isEqualTo(new BigDecimal("204.50"));
    }

    @Test
    void appliesFixedCouponOnceToTheWholeOrder() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("20.00", 1), line("30.00", 1)), "regular", "SAVE10");

        assertThat(total).isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    void roundsOnlyAfterSummingAllLines() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("0.004", 1), line("0.004", 1)), "regular", null);

        assertThat(total).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void clampsNegativeTotalAfterSummingRatherThanEachLine() {
        BigDecimal total = calculator.calculateOrderTotal(
                List.of(line("-10.00", 1), line("15.00", 1)), "regular", null);

        assertThat(total).isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void nullItemListFailsRatherThanBecomingAnEmptyOrder() {
        assertThatThrownBy(() -> calculator.calculateOrderTotal(null, "regular", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullLineFailsRatherThanBeingIgnored() {
        List<PricingCalculator.LineItem> items = Arrays.asList((PricingCalculator.LineItem) null);

        assertThatThrownBy(() -> calculator.calculateOrderTotal(items, "regular", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullPriceFailsRatherThanBecomingZero() {
        List<PricingCalculator.LineItem> items = List.of(new PricingCalculator.LineItem(null, 1));

        assertThatThrownBy(() -> calculator.calculateOrderTotal(items, "regular", null))
                .isInstanceOf(NullPointerException.class);
    }

    private PricingCalculator.LineItem line(String price, int quantity) {
        return new PricingCalculator.LineItem(new BigDecimal(price), quantity);
    }
}
