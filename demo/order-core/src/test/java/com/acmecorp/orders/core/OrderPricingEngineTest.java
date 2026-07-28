package com.acmecorp.orders.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderPricingEngineTest {

    private final OrderPricingEngine engine = new OrderPricingEngine();

    @Test
    void calculatesSubtotalAcrossMultipleLines() {
        List<OrderPricingEngine.OrderLine> lines = List.of(
            new OrderPricingEngine.OrderLine("WIDGET-1", 4, new BigDecimal("12.50")),
            new OrderPricingEngine.OrderLine("GADGET-9", 2, new BigDecimal("30.00"))
        );

        assertEquals(new BigDecimal("110.00"), engine.calcSubtotal(lines));
    }

    @Test
    void appliesStandardTaxBelowBulkThreshold() {
        List<OrderPricingEngine.OrderLine> lines = List.of(
            new OrderPricingEngine.OrderLine("WIDGET-1", 1, new BigDecimal("100.00"))
        );

        assertEquals(new BigDecimal("108.00"), engine.calcTotalWithTaxAndDiscount(lines));
    }

    @Test
    void appliesBulkDiscountAtOrAboveThreshold() {
        List<OrderPricingEngine.OrderLine> lines = List.of(
            new OrderPricingEngine.OrderLine("WIDGET-1", 1, new BigDecimal("500.00"))
        );

        // 500.00 - 5% = 475.00, + 8% tax = 513.00
        assertEquals(new BigDecimal("513.00"), engine.calcTotalWithTaxAndDiscount(lines));
    }
}
