package com.paniuthche.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskTest {
    private val past = List(90) { 1000.0 }

    @Test fun lowWhenFlowNormal() = assertEquals(Risk.LOW, riskLevel(past, List(7) { 1200.0 }))
    @Test fun mediumAtOneAndHalfTimes() = assertEquals(Risk.MEDIUM, riskLevel(past, listOf(900.0, 1600.0)))
    @Test fun highAtDouble() = assertEquals(Risk.HIGH, riskLevel(past, listOf(2100.0)))
    @Test fun lowOnEmptyOrZeroData() {
        assertEquals(Risk.LOW, riskLevel(emptyList(), listOf(5000.0)))
        assertEquals(Risk.LOW, riskLevel(List(5) { 0.0 }, listOf(5000.0)))
    }
}
