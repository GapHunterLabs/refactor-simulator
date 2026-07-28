package com.acmecorp.orders.api

import com.acmecorp.orders.core.OrderPricingEngine
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderCheckoutServiceTest {

    private val service = OrderCheckoutService()

    @Test
    fun `checkout total includes tax and bulk discount`() {
        val lines = listOf(OrderPricingEngine.OrderLine("BUNDLE-1", 1, BigDecimal("500.00")))

        assertEquals(BigDecimal("513.00"), service.checkoutTotal(lines))
    }

    @Test
    fun `preview subtotal excludes tax`() {
        val lines = listOf(OrderPricingEngine.OrderLine("WIDGET-1", 2, BigDecimal("20.00")))

        assertEquals(BigDecimal("40.00"), service.previewSubtotal(lines))
    }
}
