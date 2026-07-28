package com.acmecorp.orders.api

import com.acmecorp.orders.core.OrderPricingEngine
import java.math.BigDecimal

class OrderCheckoutService {

    private val pricingEngine = OrderPricingEngine()

    fun checkoutTotal(lines: List<OrderPricingEngine.OrderLine>): BigDecimal =
        pricingEngine.calcTotalWithTaxAndDiscount(lines)

    fun previewSubtotal(lines: List<OrderPricingEngine.OrderLine>): BigDecimal =
        pricingEngine.calcSubtotal(lines)
}
