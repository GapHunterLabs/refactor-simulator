package com.acmecorp.orders.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class OrderPricingEngine {

    private static final BigDecimal STANDARD_TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal BULK_DISCOUNT_THRESHOLD = new BigDecimal("500.00");
    private static final BigDecimal BULK_DISCOUNT_RATE = new BigDecimal("0.05");

    public BigDecimal calcSubtotal(List<OrderLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderLine line : lines) {
            subtotal = subtotal.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
        }
        return subtotal.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calcTotalWithTaxAndDiscount(List<OrderLine> lines) {
        BigDecimal subtotal = calcSubtotal(lines);
        BigDecimal discounted = applyBulkDiscountIfEligible(subtotal);
        BigDecimal tax = discounted.multiply(STANDARD_TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        return discounted.add(tax).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyBulkDiscountIfEligible(BigDecimal subtotal) {
        if (subtotal.compareTo(BULK_DISCOUNT_THRESHOLD) >= 0) {
            BigDecimal discount = subtotal.multiply(BULK_DISCOUNT_RATE);
            return subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP);
        }
        return subtotal;
    }

    public record OrderLine(String sku, int quantity, BigDecimal unitPrice) {
    }
}
