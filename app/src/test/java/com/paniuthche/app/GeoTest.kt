package com.paniuthche.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val square = listOf(24.0 to 91.0, 24.0 to 91.01, 24.01 to 91.01, 24.01 to 91.0, 24.0 to 91.0)

    @Test fun pointInsideCell() = assertTrue(pointInRing(24.005, 91.005, square))
    @Test fun pointOutsideCell() = assertFalse(pointInRing(24.02, 91.005, square))

    @Test fun riskAtPicksContainingCell() {
        val cells = listOf(RiskCell(square, 0.62, 100))
        assertEquals(0.62, riskAt(24.005, 91.005, cells), 1e-9)
        assertEquals(0.0, riskAt(25.0, 92.0, cells), 1e-9)
    }

    @Test fun nearestDistrictForFeniTown() = assertEquals("feni", nearestDistrict(23.02, 91.39).key)
    @Test fun distanceSylhetToFeniAbout210Km() = assertEquals(210.0, distanceKm(24.90, 91.87, 23.01, 91.40), 15.0)

    @Test fun levels() {
        assertEquals(AlertLevel.NONE, alertLevel(0.29))
        assertEquals(AlertLevel.MEDIUM, alertLevel(0.3))
        assertEquals(AlertLevel.HIGH, alertLevel(0.5))
    }

    @Test fun notifyOnlyOnRise() {
        assertTrue(shouldNotify(AlertLevel.MEDIUM, AlertLevel.NONE))
        assertTrue(shouldNotify(AlertLevel.HIGH, AlertLevel.MEDIUM))
        assertFalse(shouldNotify(AlertLevel.MEDIUM, AlertLevel.MEDIUM))
        assertFalse(shouldNotify(AlertLevel.NONE, AlertLevel.HIGH))
    }
}
